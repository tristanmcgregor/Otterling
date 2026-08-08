package app.otterling.proxytest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import app.otterling.proxytest.relay.IpPacket
import app.otterling.proxytest.relay.MitmExemptionPolicy
import app.otterling.proxytest.relay.ProxyConfig
import app.otterling.proxytest.relay.TcpRelayManager
import app.otterling.proxytest.relay.UdpRelayManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Standalone, ordinary (non-Device-Owner) VpnService whose only purpose is exercising the real
 * [TcpRelayManager]/[UdpRelayManager] relay code against the real mitmproxy filter server, so it
 * can be reinstalled via plain `adb install -r` on a real phone for iterative testing -- the
 * production app.otterling can't be, since its installed copy is release-signed and the debug
 * keystore used for local builds doesn't match (see git history around 2026-08-08 for why this
 * app exists at all).
 *
 * Deliberately much simpler than app.otterling's VpnFilterService: no Device Owner / always-on
 * lockdown, no domain blocklist, no alerting, no MITM-exemption UID resolution -- just enough to
 * capture a full-device default route, forward DNS to a real public resolver, and relay TCP 80/443
 * through the configured proxy exactly like production does.
 */
class ProxyTestVpnService : VpnService() {
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> Log.e(TAG, "Unhandled relay error", error) }
    private val relayExecutor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(SupervisorJob() + relayExecutor.asCoroutineDispatcher() + exceptionHandler)
    private var tunInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            startVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        val settings = ProxyTestSettings(applicationContext)
        val builder = Builder()
            .setSession("Proxy Relay Test")
            .setMtu(MTU)
            .addAddress(VIRTUAL_IP, 24)
            .addDnsServer(DNS_SERVER_IP)
            .addRoute("0.0.0.0", 0)
            .setMetered(false)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to exclude own package from VPN", error)
        }

        tunInterface = try {
            builder.establish()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to establish VPN tunnel", error)
            null
        }
        val tun = tunInterface
        if (tun == null) {
            isTunnelUp = false
            running.set(false)
            stopSelf()
            return
        }
        isTunnelUp = true
        Log.i(TAG, "Tunnel up -- proxy ${settings.host}:${settings.port} user=${settings.user}")
        scope.launch {
            runCatching { runPacketLoop(tun, settings) }
                .onFailure { Log.e(TAG, "Packet loop crashed", it) }
            isTunnelUp = false
        }
    }

    private fun runPacketLoop(tun: ParcelFileDescriptor, settings: ProxyTestSettings) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val writeLock = Mutex()
        val writeToTun: suspend (ByteArray) -> Unit = { bytes ->
            try {
                writeLock.withLock { output.write(bytes) }
            } catch (error: IOException) {
                Log.w(TAG, "Failed writing to tun", error)
            }
        }
        val isBlockedDestination: (String) -> Boolean = { false } // no filtering -- relay-only test harness
        val proxyConfig = ProxyConfig(
            enabled = true,
            host = settings.host,
            port = settings.port,
            user = settings.user,
            password = settings.password,
        )

        val tcpRelay = TcpRelayManager(
            scope = scope,
            protect = { socket -> protect(socket) },
            writeToTun = writeToTun,
            isBlockedDestination = isBlockedDestination,
            proxyConfig = proxyConfig,
            resolveHostname = { null }, // cosmetic-only in the real code too; skipped here for simplicity
            mitmExemptHostSuffixes = MitmExemptionPolicy.DEFAULT_HOST_SUFFIXES,
        )
        val udpRelay = UdpRelayManager(
            scope = scope,
            protect = { socket -> protect(socket) },
            writeToTun = writeToTun,
            isBlockedDestination = isBlockedDestination,
        )

        var consecutiveEmptyReads = 0
        while (running.get()) {
            val buffer = ByteArray(MTU + 100)
            val length = try {
                input.read(buffer)
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "tun read failed", error)
                break
            }
            if (length < 0) {
                if (running.get()) Log.w(TAG, "tun read hit EOF")
                break
            }
            if (length == 0) {
                consecutiveEmptyReads++
                Thread.sleep(EMPTY_READ_BACKOFF_MS)
                continue
            }
            consecutiveEmptyReads = 0

            val packet = IpPacket.parse(buffer, length) ?: continue
            when (packet.protocol) {
                IpPacket.PROTOCOL_UDP -> {
                    when {
                        packet.destinationPort == DNS_PORT -> scope.launch { handleDnsPacket(packet, writeToTun) }
                        // Force HTTPS onto the TCP relay path instead of slipping past the proxy over
                        // QUIC/HTTP3, same as production -- this is what the whole app exists to test.
                        packet.destinationPort == QUIC_PORT -> {}
                        else -> udpRelay.handle(packet)
                    }
                }
                IpPacket.PROTOCOL_TCP -> tcpRelay.handle(packet)
                else -> {}
            }
        }
    }

    /** Minimal DNS forwarder: straight to a real public resolver, no blocklist/cloud-filter lookup
     *  -- this app only exists to test the TCP relay/proxy path, not DNS-level filtering. */
    private suspend fun handleDnsPacket(packet: IpPacket, writeToTun: suspend (ByteArray) -> Unit) {
        val response = try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val upstream = InetSocketAddress(InetAddress.getByName(UPSTREAM_DNS), DNS_PORT)
                socket.send(DatagramPacket(packet.payload, packet.payload.size, upstream))
                val responseBuffer = ByteArray(2048)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)
                responseBuffer.copyOf(responsePacket.length)
            }
        } catch (error: IOException) {
            Log.w(TAG, "DNS query failed", error)
            null
        } ?: return
        try {
            writeToTun(packet.buildUdpReply(response))
        } catch (error: IOException) {
            Log.w(TAG, "Failed writing DNS reply to tun", error)
        }
    }

    override fun onDestroy() {
        running.set(false)
        isTunnelUp = false
        scope.cancel()
        relayExecutor.shutdownNow()
        tunInterface?.let { runCatching { it.close() } }
        tunInterface = null
        super.onDestroy()
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Proxy relay test VPN active", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxy Relay Test")
            .setContentText("Relaying through the test proxy")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "ProxyTestVpn"
        private const val CHANNEL_ID = "proxytest_vpn"
        private const val NOTIFICATION_ID = 1
        private const val VIRTUAL_IP = "10.222.111.1"
        private const val DNS_SERVER_IP = "10.222.111.2"
        private const val DNS_PORT = 53
        private const val QUIC_PORT = 443
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val MTU = 16384
        private const val EMPTY_READ_BACKOFF_MS = 20L

        /** Polled by MainActivity -- same process, so a plain volatile is enough; no need for a
         *  broadcast/binder round trip just to show "running" status in this disposable tool. */
        @Volatile var isTunnelUp: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProxyTestVpnService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyTestVpnService::class.java))
        }
    }
}
