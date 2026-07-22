package au.com.tbmcgregor.bwparker.familyguard.content

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Local, always-on VpnService that filters DNS lookups (plus a short list of known public
 * DNS-over-HTTPS/DoT resolver IPs) against a downloaded domain blocklist ([DomainBlocklistManager]),
 * without decrypting or inspecting any actual web traffic. General app traffic is never routed
 * through this VPN -- only the virtual DNS address and the hardcoded resolver IPs are added as
 * routes, so there's no need to implement a full TCP/IP NAT stack for everything else.
 *
 * Registered as the device's mandatory VPN via [VpnFilterManager], which uses Device Owner's
 * `DevicePolicyManager.setAlwaysOnVpnPackage(..., lockdownEnabled = true)` -- once set, Android
 * blocks all network access unless this service is running, and the always-on VPN setting itself
 * is locked out of the user-facing Settings UI.
 *
 * Some browsers' built-in "Secure DNS"/DNS-over-HTTPS features may fail to load pages while this
 * is active, since their hardcoded resolver IP gets dropped rather than falling through to the
 * (filtered) system resolver -- same trade-off as disabling Secure DNS in Chrome.
 */
class VpnFilterService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var workerJob: Job? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        val blocklist = DomainBlocklistManager(applicationContext)
        val builder = Builder()
            .setSession("Family Device Guard Filter")
            .addAddress(VIRTUAL_IP, 32)
            .addDnsServer(VIRTUAL_IP)
            .addRoute(VIRTUAL_IP, 32)
        KNOWN_DOH_IPS.forEach { ip -> runCatching { builder.addRoute(ip, 32) } }

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

    private fun runPacketLoop(tun: ParcelFileDescriptor, blocklist: DomainBlocklistManager) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "tun read failed", error)
                break
            }
            if (length <= 0) continue

            val packet = IpPacket.parse(buffer, length) ?: continue
            if (packet.protocol == IpPacket.PROTOCOL_UDP && packet.destinationPort == DNS_PORT) {
                handleDnsPacket(packet, output, blocklist)
            }
            // Everything else that reached the tun is a known public DoH/DoT IP we deliberately
            // routed here to drop -- no reply is sent, so those connections just fail/time out.
        }
    }

    private fun handleDnsPacket(packet: IpPacket, output: FileOutputStream, blocklist: DomainBlocklistManager) {
        val query = DnsMessage.parseQuery(packet.udpPayload) ?: return
        val response = if (blocklist.isBlocked(query.questionName)) {
            DnsMessage.buildBlockedResponse(packet.udpPayload)
        } else {
            forwardToUpstream(packet.udpPayload)
        } ?: return

        try {
            output.write(packet.buildUdpReply(response))
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
        workerJob?.cancel()
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
        private const val DNS_PORT = 53
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val UPSTREAM_TIMEOUT_MS = 5_000

        /** Public DoH/DoT resolver IPs -- dropped so apps can't dodge filtering by hardcoding
         *  their own DNS instead of using the (filtered) system resolver set above. */
        private val KNOWN_DOH_IPS = setOf(
            "1.1.1.1", "1.0.0.1", // Cloudflare
            "8.8.8.8", "8.8.4.4", // Google
            "9.9.9.9", "149.112.112.112", // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VpnFilterService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VpnFilterService::class.java))
        }
    }
}
