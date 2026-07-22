package au.com.tbmcgregor.bwparker.familyguard.content

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NAT-relays TCP connections captured by [VpnFilterService]'s tun: for each new SYN, opens a real
 * (protected, i.e. bypassing the tun) [Socket] to the actual destination, and bridges bytes
 * between that socket and a minimal hand-rolled TCP peer speaking to the client over the tun.
 *
 * This exists because a [android.net.VpnService] that captures all app traffic (needed so every
 * app's DNS goes through our filter) but only has routes for a handful of specific IPs makes every
 * *other* destination unreachable for captured apps -- not "falls back to the real network" as
 * you might assume, just broken. Capturing everything via a default route and relaying it back out
 * ourselves is the only way to filter DNS/known-bypass-IPs without breaking every other connection.
 *
 * Deliberately simplified vs. a spec-compliant TCP stack: no retransmission timers, no window
 * scaling, no SACK, no congestion control -- since every "wire" on the client side of this relay is
 * the local tun (effectively zero loss/reordering), a client segment is trusted to arrive at most
 * once and in order, and any oddities (stray packets, failed connects) are resolved with an
 * immediate RST rather than perfect RFC 793 behaviour. This is the same trade-off small
 * from-scratch Android "local VPN" content filters (DNS66, PersonalDNSFilter, etc.) make instead of
 * embedding a full network stack.
 */
class TcpRelayManager(
    private val scope: CoroutineScope,
    private val protect: (Socket) -> Boolean,
    private val writeToTun: suspend (ByteArray) -> Unit,
    private val isBlockedDestination: (String) -> Boolean,
) {
    private data class FlowKey(val srcIp: String, val srcPort: Int, val dstIp: String, val dstPort: Int)

    private class Connection(val key: FlowKey, val templatePacket: IpPacket) {
        val inbox = Channel<IpPacket>(Channel.UNLIMITED)

        @Volatile var socket: Socket? = null

        @Volatile var clientNextSeq: Long = 0

        @Volatile var serverSeq: Long = 0

        // Flow control for the socket -> tun direction: how far the client's own kernel TCP
        // stack has acknowledged our stream, and how much more buffer space it's currently
        // advertising beyond that point. Without honoring this, a fast real-socket read (e.g. a
        // burst of audio data) gets blasted onto the tun regardless of whether the client can
        // buffer it; since there's no retransmission here, anything sent past its window is just
        // silently dropped by the client's own kernel and never recovered -- from the app's point
        // of view the stream just stalls forever. Starts at WINDOW_SIZE (this class's own
        // optimistic default) since the real value only arrives once the first client ACK does.
        @Volatile var clientAcked: Long = 0
        @Volatile var clientWindow: Int = WINDOW_SIZE
    }

    private val connections = ConcurrentHashMap<FlowKey, Connection>()

    /**
     * Must be called synchronously, in the exact order packets were read off the tun (not from an
     * independently-scheduled coroutine) -- per-connection ordering after this point is guaranteed
     * by each [Connection.inbox] having exactly one consumer, but that guarantee only holds if
     * packets are enqueued onto it in the right order to begin with.
     */
    fun handle(packet: IpPacket) {
        val key = FlowKey(packet.sourceAddress, packet.sourcePort, packet.destinationAddress, packet.destinationPort)
        val existing = connections[key]
        if (existing != null) {
            if (packet.isRst) {
                connections.remove(key)
                existing.inbox.close()
                existing.socket?.let { socket -> scope.launch { runCatching { socket.close() } } }
            } else {
                existing.inbox.trySend(packet)
            }
            return
        }
        if (packet.isRst) return
        if (packet.isSyn) {
            Log.d(TAG, "SYN ${key.srcIp}:${key.srcPort} -> ${key.dstIp}:${key.dstPort}")
            val connection = Connection(key, packet)
            if (connections.putIfAbsent(key, connection) == null) {
                scope.launch { establish(connection, packet) }
            }
        } else {
            // No known connection and this isn't the start of one (e.g. a stray packet left over
            // from before our process restarted) -- RST so the client's TCP stack doesn't hang.
            scope.launch { writeToTun(rstFor(packet)) }
        }
    }

    private suspend fun establish(connection: Connection, synPacket: IpPacket) {
        if (isBlockedDestination(connection.key.dstIp)) {
            connections.remove(connection.key)
            writeToTun(rstFor(synPacket))
            return
        }
        val socket = Socket()
        val connected = try {
            // A freshly-constructed Socket has no underlying file descriptor until it's bound (or
            // connected) -- protect() silently fails on it otherwise, since there's nothing to mark.
            socket.bind(InetSocketAddress(0))
            if (!protect(socket)) throw IOException("VpnService.protect() failed")
            withContext(Dispatchers.IO) {
                socket.connect(InetSocketAddress(InetAddress.getByName(connection.key.dstIp), connection.key.dstPort), CONNECT_TIMEOUT_MS)
            }
            socket.tcpNoDelay = true
            true
        } catch (error: Exception) {
            Log.w(TAG, "TCP connect to ${connection.key.dstIp}:${connection.key.dstPort} failed", error)
            false
        }
        if (!connected) {
            connections.remove(connection.key)
            runCatching { socket.close() }
            writeToTun(rstFor(synPacket))
            return
        }

        connection.socket = socket
        connection.clientNextSeq = (synPacket.tcpSeq + 1) and SEQ_MASK
        val isn = Random.nextInt().toLong() and SEQ_MASK
        connection.serverSeq = (isn + 1) and SEQ_MASK
        // Anchor the "acked" edge to the same point so the first waitForWindow() check (which
        // may run before the client's handshake-completing ACK has been processed) doesn't see a
        // bogus multi-gigabyte "in-flight" gap between a real serverSeq and a zeroed clientAcked.
        connection.clientAcked = connection.serverSeq
        writeToTun(synPacket.buildTcpSegment(isn, connection.clientNextSeq, IpPacket.TCP_SYN or IpPacket.TCP_ACK, WINDOW_SIZE))

        scope.launch { consumeInbox(connection) }
        scope.launch { relayFromSocket(connection) }
    }

    private suspend fun consumeInbox(connection: Connection) {
        for (packet in connection.inbox) {
            handleEstablished(connection, packet)
        }
    }

    private suspend fun handleEstablished(connection: Connection, packet: IpPacket) {
        val socket = connection.socket ?: return
        if (packet.isAck) {
            // Sequence numbers wrap at 2^32; only move the "acked" edge forward using a signed-diff
            // comparison, so a stale/out-of-order ACK can't look like the window jumped backwards.
            val delta = (packet.tcpAck - connection.clientAcked) and SEQ_MASK
            if (delta in 1 until WRAP_THRESHOLD) {
                connection.clientAcked = packet.tcpAck
            }
            connection.clientWindow = packet.tcpWindow
        }
        if (packet.payload.isNotEmpty() && packet.tcpSeq == connection.clientNextSeq) {
            try {
                withContext(Dispatchers.IO) { socket.getOutputStream().write(packet.payload) }
            } catch (error: IOException) {
                closeConnection(connection, sendRst = true)
                return
            }
            connection.clientNextSeq = (connection.clientNextSeq + packet.payload.size) and SEQ_MASK
        }
        if (packet.payload.isNotEmpty() || packet.isFin) {
            var ackValue = connection.clientNextSeq
            if (packet.isFin) {
                ackValue = (ackValue + 1) and SEQ_MASK
                connection.clientNextSeq = ackValue
                runCatching { socket.shutdownOutput() }
            }
            writeToTun(packet.buildTcpSegment(connection.serverSeq, ackValue, IpPacket.TCP_ACK, WINDOW_SIZE))
        }
    }

    /**
     * Blocks (without holding up any other connection -- each runs in its own coroutine) until
     * the client's advertised window has room for [neededBytes] more unacknowledged bytes, per
     * its most recent ACK. Polls rather than using a signal, since a window update is just an
     * ordinary incoming ACK processed on a different coroutine ([consumeInbox]) with no natural
     * place to hang a callback; the poll interval is short enough not to add noticeable latency.
     */
    private suspend fun waitForWindow(connection: Connection, neededBytes: Int) {
        while (true) {
            val inFlight = (connection.serverSeq - connection.clientAcked) and SEQ_MASK
            val available = connection.clientWindow.toLong() - inFlight
            if (available >= neededBytes) return
            delay(WINDOW_POLL_DELAY_MS)
        }
    }

    private suspend fun relayFromSocket(connection: Connection) {
        val socket = connection.socket ?: return
        val buffer = ByteArray(MAX_SEGMENT_SIZE)
        try {
            val input = socket.getInputStream()
            while (true) {
                val read = withContext(Dispatchers.IO) { input.read(buffer) }
                if (read < 0) break
                waitForWindow(connection, read)
                writeToTun(
                    connection.templatePacket.buildTcpSegment(
                        connection.serverSeq,
                        connection.clientNextSeq,
                        IpPacket.TCP_ACK or IpPacket.TCP_PSH,
                        WINDOW_SIZE,
                        buffer.copyOf(read),
                    ),
                )
                connection.serverSeq = (connection.serverSeq + read) and SEQ_MASK
            }
            writeToTun(
                connection.templatePacket.buildTcpSegment(
                    connection.serverSeq,
                    connection.clientNextSeq,
                    IpPacket.TCP_FIN or IpPacket.TCP_ACK,
                    WINDOW_SIZE,
                ),
            )
            connection.serverSeq = (connection.serverSeq + 1) and SEQ_MASK
            closeConnection(connection, sendRst = false)
        } catch (error: IOException) {
            closeConnection(connection, sendRst = true)
        }
    }

    private suspend fun closeConnection(connection: Connection, sendRst: Boolean) {
        connections.remove(connection.key)
        connection.inbox.close()
        if (sendRst) {
            writeToTun(
                connection.templatePacket.buildTcpSegment(
                    connection.serverSeq,
                    connection.clientNextSeq,
                    IpPacket.TCP_RST or IpPacket.TCP_ACK,
                    0,
                ),
            )
        }
        runCatching { connection.socket?.close() }
    }

    /** RST reply for a SYN we're refusing (blocked destination or failed connect) or a stray
     * non-SYN packet with no known connection -- built straight from the wire packet, not any
     * tracked connection state, since none exists yet. */
    private fun rstFor(packet: IpPacket): ByteArray {
        val ackValue = (packet.tcpSeq + maxOf(packet.payload.size, if (packet.isSyn) 1 else 0)) and SEQ_MASK
        return packet.buildTcpSegment(seq = 0, ack = ackValue, flags = IpPacket.TCP_RST or IpPacket.TCP_ACK, window = 0)
    }

    private companion object {
        const val TAG = "TcpRelayManager"
        const val WINDOW_SIZE = 65535
        const val MAX_SEGMENT_SIZE = 1400
        const val CONNECT_TIMEOUT_MS = 8_000
        const val SEQ_MASK = 0xFFFFFFFFL
        const val WRAP_THRESHOLD = 0x80000000L
        const val WINDOW_POLL_DELAY_MS = 10L
    }
}
