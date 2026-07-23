package au.com.tbmcgregor.bwparker.familyguard.content

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local, always-on VpnService that filters DNS lookups (plus a short list of known public
 * DNS-over-HTTPS/DoT resolver IPs, refused outright) against a downloaded domain blocklist
 * ([DomainBlocklistManager]), without decrypting or inspecting any actual web traffic.
 *
 * Captures a full default route (every IPv4 destination) because a [VpnService] that captures all
 * app traffic but only has routes for a handful of specific IPs makes every *other* destination
 * unreachable for captured apps -- not "falls back to the real network" like an earlier version of
 * this class assumed, just broken (this is what caused otherwise-unrelated apps, e.g. Spotify, to
 * report "no internet" the moment this VPN turned on). Since DNS filtering needs *every* app's
 * traffic funneled through here anyway (so nothing can bypass it), the only way to do that without
 * breaking everything else is to also relay everything else back out ourselves --
 * [TcpRelayManager]/[UdpRelayManager] do that: real destinations get a real (protected) socket
 * opened on this device and bytes are bridged transparently in both directions; only DNS (port 53)
 * and the hardcoded DoH/DoT IPs get special treatment.
 *
 * Registered as the device's mandatory VPN via [VpnFilterManager], which uses Device Owner's
 * `DevicePolicyManager.setAlwaysOnVpnPackage(..., lockdownEnabled = true)` -- once set, Android
 * blocks all network access unless this service is running, and the always-on VPN setting itself
 * is locked out of the user-facing Settings UI.
 *
 * IPv6 isn't captured (no IPv6 address/route is ever added to the [Builder]), so it's blocked
 * outright for captured apps rather than relayed -- acceptable for now since this only matters on
 * networks that actually offer global IPv6 routing to begin with.
 *
 * Some browsers' built-in "Secure DNS"/DNS-over-HTTPS features may fail to load pages while this
 * is active, since their hardcoded resolver IP gets refused rather than falling through to the
 * (filtered) system resolver -- same trade-off as disabling Secure DNS in Chrome.
 */
class VpnFilterService : VpnService() {
    // Without this, an uncaught exception in any single relayed connection's coroutine (there are
    // many, one+ per TCP/UDP flow) crashes this whole process by default -- taking down every
    // other in-flight connection and the VPN itself, not just the one that hit a bug.
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> Log.e(TAG, "Unhandled relay error", error) }
    // Dispatchers.IO is capped at ~64 concurrent threads (tuned for short-lived I/O bursts), but
    // this relay needs one thread *per open connection* for as long as a blocking socket.connect()/
    // read()/write() call is in flight -- with anything beyond ~64 simultaneous flows (trivially
    // reached by e.g. a speed-test site opening dozens of parallel probe connections), the excess
    // ones queue behind whichever 64 happen to be running, adding multi-second delays that read to
    // the client as a stalled/failed TLS handshake or an outright connect timeout, even though nothing
    // was actually wrong with the connection itself. An unbounded cached pool removes that ceiling.
    private val relayExecutor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(SupervisorJob() + relayExecutor.asCoroutineDispatcher() + exceptionHandler)
    private var tunInterface: ParcelFileDescriptor? = null
    private var workerJob: Job? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_REESTABLISH, false) == true) {
            reestablish()
        } else if (running.compareAndSet(false, true)) {
            startVpn()
        }
        return START_STICKY
    }

    /** Tears down the current tunnel and builds a fresh one so a changed bypass list (or blocklist)
     * takes effect without fully stopping the service / dropping the always-on registration. */
    private fun reestablish() {
        workerJob?.cancel()
        tunInterface?.let { runCatching { it.close() } }
        tunInterface = null
        running.set(true)
        startVpn()
    }

    private fun startVpn() {
        val blocklist = DomainBlocklistManager(applicationContext)
        val builder = Builder()
            .setSession("Family Device Guard Filter")
            .setMtu(MTU)
            // A /32 tun address with the DNS server pointed at that *same* address was the bug:
            // the kernel treats traffic to an interface's own local address as local delivery, so
            // it never actually traverses the tun device -- our packet-read loop never sees it.
            // Queries to any *other* address (even one nothing else owns, like .2 here) genuinely
            // get routed out over tun0 by the 0.0.0.0/0 route below and hit our relay correctly.
            // This is why apps with a hardcoded fallback resolver (e.g. Chrome/WhatsApp querying
            // 8.8.8.8/8.8.4.4 directly) worked while everything using the network's *configured*
            // DNS server (i.e. nearly everything else, including Spotify) silently got NODATA.
            .addAddress(VIRTUAL_IP, 24)
            .addDnsServer(DNS_SERVER_IP)
            .addRoute("0.0.0.0", 0)
            // VpnService treats the tunnel as metered by default. Left unset, apps that respect
            // Data Saver / "restrict background data" (e.g. Spotify) get silently network-blocked
            // by netd over this VPN even though the underlying Wi-Fi/cellular network is unmetered.
            .setMetered(false)
        // Apps the user has exempted (e.g. Android Auto) get routed over the normal network instead
        // of captured here -- some apps simply break under any VPN.
        applyBypassApps(builder)

        tunInterface = try {
            builder.establish()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to establish VPN tunnel", error)
            null
        }

        val tun = tunInterface
        if (tun == null) {
            running.set(false)
            return
        }
        workerJob = scope.launch {
            runCatching { runPacketLoop(tun, blocklist) }
                .onFailure { Log.e(TAG, "Packet loop crashed", it) }
        }
    }

    private fun applyBypassApps(builder: Builder) {
        val bypass = VpnBypassManager(applicationContext).bypassPackages()
        bypass.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
                Log.i(TAG, "VPN bypass: $pkg routed outside the tunnel")
            } catch (error: PackageManager.NameNotFoundException) {
                Log.w(TAG, "VPN bypass app not installed, skipping: $pkg", error)
            }
        }
    }

    private fun runPacketLoop(tun: ParcelFileDescriptor, blocklist: DomainBlocklistManager) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val writeLock = Mutex()
        // Centralized so every caller (DNS/TCP/UDP relay) is protected: a write can fail with EIO
        // if the tun gets torn down (e.g. the VPN toggled off) while a background relay coroutine
        // is mid-write. Uncaught, that IOException propagates to the coroutine dispatcher's thread
        // and crashes the whole process -- taking down every other in-flight connection with it,
        // not just the one that failed.
        val writeToTun: suspend (ByteArray) -> Unit = { bytes ->
            try {
                writeLock.withLock { output.write(bytes) }
            } catch (error: IOException) {
                Log.w(TAG, "Failed writing to tun", error)
            }
        }
        val isBlockedDestination: (String) -> Boolean = { ip -> ip in KNOWN_DOH_IPS }

        val tcpRelay = TcpRelayManager(
            scope = scope,
            protect = { socket -> protect(socket) },
            writeToTun = writeToTun,
            isBlockedDestination = isBlockedDestination,
        )
        val udpRelay = UdpRelayManager(
            scope = scope,
            protect = { socket -> protect(socket) },
            writeToTun = writeToTun,
            isBlockedDestination = isBlockedDestination,
        )

        while (running.get()) {
            // A fresh buffer per read -- the previous one may still be in flight on a background
            // coroutine, since DNS/TCP/UDP handling all happen off this loop.
            val buffer = ByteArray(MTU + 100) // headroom over MTU for IP/TCP headers on inbound client segments
            val length = try {
                input.read(buffer)
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "tun read failed", error)
                break
            }
            if (length <= 0) continue

            val packet = IpPacket.parse(buffer, length)
            if (packet == null) {
                Log.d(TAG, "tun: unparseable packet, $length bytes, first byte 0x${"%02x".format(buffer[0])}")
                continue
            }
            when (packet.protocol) {
                IpPacket.PROTOCOL_UDP -> {
                    if (packet.destinationPort == DNS_PORT) {
                        // Handled on its own coroutine so one slow/stalled upstream lookup can't
                        // stall this read loop and pile up every other in-flight query behind it
                        // (this alone used to be enough to make apps that fire off many DNS
                        // lookups in quick succession, e.g. Spotify resolving several
                        // edge/access-point hostnames at once, see lookups time out).
                        scope.launch { handleDnsPacket(packet, writeToTun, blocklist) }
                    } else {
                        udpRelay.handle(packet)
                    }
                }
                IpPacket.PROTOCOL_TCP -> tcpRelay.handle(packet)
                else -> {} // e.g. ICMP -- not relayed, no reply sent
            }
        }
    }

    private suspend fun handleDnsPacket(
        packet: IpPacket,
        writeToTun: suspend (ByteArray) -> Unit,
        blocklist: DomainBlocklistManager,
    ) {
        val query = DnsMessage.parseQuery(packet.payload)
        if (query == null) {
            Log.d(TAG, "DNS: unparseable query (${packet.payload.size} bytes) from ${packet.sourceAddress}:${packet.sourcePort}")
            return
        }
        val blocked = blocklist.isBlocked(query.questionName)
        val response = if (blocked) {
            DnsMessage.buildBlockedResponse(packet.payload)
        } else {
            forwardToUpstream(packet.payload)
        }
        if (response == null) {
            Log.d(TAG, "DNS: '${query.questionName}' upstream lookup failed/timed out -- no reply sent")
            return
        }
        Log.d(TAG, "DNS: '${query.questionName}' blocked=$blocked -> replying with ${response.size} bytes")

        try {
            writeToTun(packet.buildUdpReply(response))
        } catch (error: IOException) {
            Log.w(TAG, "Failed writing DNS reply to tun", error)
        }
    }

    /** Uses a protect()-ed socket so this outbound query doesn't loop back into the VPN itself. */
    private fun forwardToUpstream(queryBytes: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val upstream = InetSocketAddress(InetAddress.getByName(UPSTREAM_DNS), DNS_PORT)
                socket.send(DatagramPacket(queryBytes, queryBytes.size, upstream))
                val responseBuffer = ByteArray(2048)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)
                responseBuffer.copyOf(responsePacket.length)
            }
        } catch (error: IOException) {
            Log.w(TAG, "Upstream DNS query failed", error)
            null
        }
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel() // tears down every in-flight TCP/UDP relay connection too, not just the read loop
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
            NotificationChannel(CHANNEL_ID, "Content filter VPN active", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Device Guard")
            .setContentText("Content filtering is active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VpnFilterService"
        private const val CHANNEL_ID = "vpn_content_filter"
        private const val NOTIFICATION_ID = 1002
        private const val VIRTUAL_IP = "10.111.222.1"
        private const val DNS_SERVER_IP = "10.111.222.2"
        private const val DNS_PORT = 53
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        // Real Ethernet/Wi-Fi framing never sees this value: these "packets" only ever travel
        // between our relay code and the local kernel over the virtual tun device, since real
        // segmentation onto the actual network happens transparently inside the OS's own TCP/IP
        // stack when we call socket.write() on a real Socket/DatagramSocket. A too-small MTU here
        // (this used to be the standard Ethernet 1500) forces every relayed byte through many more
        // 1400-ish-byte packets than necessary, and each one pays a fixed per-packet cost (mutex
        // acquisition, a tun write() syscall, a coroutine dispatch) -- that fixed cost, multiplied
        // by many more packets, was capping real download throughput to a small fraction of the
        // underlying link's actual speed even once flow control/window scaling were fixed.
        private const val MTU = 16384

        /** Public DoH/DoT resolver IPs -- refused (RST/dropped) so apps can't dodge filtering by
         *  hardcoding their own DNS instead of using the (filtered) system resolver set above. */
        private val KNOWN_DOH_IPS = setOf(
            "1.1.1.1", "1.0.0.1", // Cloudflare
            "8.8.8.8", "8.8.4.4", // Google
            "9.9.9.9", "149.112.112.112", // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
        )

        private const val EXTRA_REESTABLISH = "reestablish"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VpnFilterService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VpnFilterService::class.java))
        }

        /** Rebuilds the tunnel in place so a changed bypass/blocklist takes effect immediately,
         * without dropping the always-on registration. No-op if the service isn't running. */
        fun reestablish(context: Context) {
            context.startForegroundService(
                Intent(context, VpnFilterService::class.java).putExtra(EXTRA_REESTABLISH, true),
            )
        }
    }
}
