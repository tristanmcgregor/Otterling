package au.com.tbmcgregor.bwparker.familyguard.content

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * NAT-relays non-DNS UDP traffic captured by [VpnFilterService]'s tun (e.g. QUIC/HTTP3, which a
 * lot of modern apps and CDNs use instead of TCP) -- same rationale as [TcpRelayManager]: a VPN
 * that captures every app's traffic but only routes a few specific IPs makes everything else
 * unreachable, not "falls back to the real network".
 *
 * Much simpler than the TCP side since UDP has no handshake or ordering guarantees to emulate:
 * each new (srcIp,srcPort,dstIp,dstPort) tuple gets one protected [DatagramSocket] connected to
 * the real destination; datagrams are relayed verbatim in both directions until the flow goes
 * idle.
 */
class UdpRelayManager(
    private val scope: CoroutineScope,
    private val protect: (DatagramSocket) -> Boolean,
    private val writeToTun: suspend (ByteArray) -> Unit,
    private val isBlockedDestination: (String) -> Boolean,
) {
    private data class FlowKey(val srcIp: String, val srcPort: Int, val dstIp: String, val dstPort: Int)

    private class Flow(val templatePacket: IpPacket, val socket: DatagramSocket) {
        @Volatile var lastActivityMillis: Long = System.currentTimeMillis()
    }

    private val flows = ConcurrentHashMap<FlowKey, Flow>()

    /** Must be called synchronously from the tun read loop (not from an independently-scheduled
     * coroutine), same reasoning as [TcpRelayManager.handle] -- the map lookup/insert itself needs
     * to happen in tun-read order even though the actual I/O is dispatched to background coroutines. */
    fun handle(packet: IpPacket) {
        val key = FlowKey(packet.sourceAddress, packet.sourcePort, packet.destinationAddress, packet.destinationPort)
        val existing = flows[key]
        if (existing != null) {
            existing.lastActivityMillis = System.currentTimeMillis()
            scope.launch { send(existing, packet.payload) }
            return
        }
        if (isBlockedDestination(key.dstIp)) return // silent drop -- same as the old known-DoH-IP behaviour

        val socket = try {
            DatagramSocket().apply { soTimeout = IDLE_TIMEOUT_MS.toInt() }
        } catch (error: IOException) {
            return
        }
        if (!protect(socket)) {
            runCatching { socket.close() }
            return
        }
        val flow = Flow(packet, socket)
        if (flows.putIfAbsent(key, flow) != null) {
            // Lost a race to a flow created concurrently for the same key -- drop ours, use theirs.
            runCatching { socket.close() }
            flows[key]?.let { winner -> scope.launch { send(winner, packet.payload) } }
            return
        }
        scope.launch { send(flow, packet.payload) }
        scope.launch { receiveLoop(key, flow) }
    }

    private suspend fun send(flow: Flow, payload: ByteArray) {
        try {
            // See TcpRelayManager's establish() for why blocking directly (no withContext) is
            // fine here: [scope] already runs on a dedicated unbounded pool, not Dispatchers.IO.
            val destination = InetSocketAddress(
                InetAddress.getByName(flow.templatePacket.destinationAddress),
                flow.templatePacket.destinationPort,
            )
            flow.socket.send(DatagramPacket(payload, payload.size, destination))
        } catch (error: IOException) {
            // Best-effort -- the receive loop's own I/O failures are what actually tear the flow down.
        }
    }

    private suspend fun receiveLoop(key: FlowKey, flow: Flow) {
        val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        try {
            while (true) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    flow.socket.receive(datagram)
                } catch (timeout: SocketTimeoutException) {
                    if (System.currentTimeMillis() - flow.lastActivityMillis > IDLE_TIMEOUT_MS) break else continue
                }
                flow.lastActivityMillis = System.currentTimeMillis()
                writeToTun(flow.templatePacket.buildUdpReply(buffer.copyOf(datagram.length)))
            }
        } catch (error: IOException) {
            // fall through to cleanup
        } finally {
            flows.remove(key)
            runCatching { flow.socket.close() }
        }
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 120_000L
        const val MAX_DATAGRAM_SIZE = 65_507
    }
}
