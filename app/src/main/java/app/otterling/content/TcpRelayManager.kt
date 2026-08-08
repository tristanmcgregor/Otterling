package app.otterling.content

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Filter proxy connection details for [TcpRelayManager] -- when [enabled], every TCP flow to port
 * 80 or 443 is CONNECT-tunneled through [host]:[port] (HTTP Basic proxy auth via [user]/[password])
 * instead of being relayed directly to its real destination. See [CloudFilterSettings] for where
 * this comes from.
 */
data class ProxyConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
)

/**
 * NAT-relays TCP connections captured by [VpnFilterService]'s tun: for each new SYN, opens a real
 * (protected, i.e. bypassing the tun) [Socket] and bridges bytes between that socket and a minimal
 * hand-rolled TCP peer speaking to the client over the tun. When [proxyConfig] is enabled and the
 * destination port is 80 or 443, that socket connects to the filter proxy instead of the real
 * destination, performs an HTTP CONNECT handshake first, and only then starts bridging bytes --
 * from that point on the proxy sees exactly what a direct connection's peer would have, since the
 * bytes past the CONNECT handshake are the same TLS/HTTP the client itself sent. A failed CONNECT
 * (auth rejected, proxy unreachable, etc.) fails that flow closed (RST) rather than silently
 * connecting directly, so a proxy outage can't be used to bypass filtering.
 *
 * This exists because a [android.net.VpnService] that captures all app traffic (needed so every
 * app's DNS goes through our filter) but only has routes for a handful of specific IPs makes every
 * *other* destination unreachable for captured apps -- not "falls back to the real network" as
 * you might assume, just broken. Capturing everything via a default route and relaying it back out
 * ourselves is the only way to filter DNS/known-bypass-IPs without breaking every other connection.
 *
 * Deliberately simplified vs. a spec-compliant TCP stack: no retransmission timers, no SACK, no
 * congestion control -- since every "wire" on the client side of this relay is the local tun
 * (effectively zero loss/reordering), a client segment is trusted to arrive at most once and in
 * order, and any oddities (stray packets, failed connects) are resolved with an immediate RST
 * rather than perfect RFC 793 behaviour. This is the same trade-off small from-scratch Android
 * "local VPN" content filters (DNS66, PersonalDNSFilter, etc.) make instead of embedding a full
 * network stack.
 *
 * Window scaling (RFC 1323) *is* implemented, and matters a lot here: without it every connection
 * through this relay is hard-capped to a 65535-byte TCP window, which limits throughput to
 * roughly window/RTT regardless of the real link speed (e.g. ~25Mbps at a fairly ordinary 20ms
 * RTT) -- a real, previously-invisible bottleneck, since it only bites once DNS/TCP relaying
 * itself is actually working end-to-end for high-throughput transfers.
 */
class TcpRelayManager(
    private val scope: CoroutineScope,
    private val protect: (Socket) -> Boolean,
    private val writeToTun: suspend (ByteArray) -> Unit,
    private val isBlockedDestination: (String) -> Boolean,
    private val proxyConfig: ProxyConfig,
    private val resolveHostname: (String) -> String?,
    private val resolveOwnerUid: (String, Int, String, Int) -> Int? = { _, _, _, _ -> null },
    private val mitmExemptUids: Set<Int> = emptySet(),
    private val mitmExemptHostSuffixes: Set<String> = emptySet(),
    /** Called when a just-closed *proxied* connection's shape looks like a certificate-pinning
     *  rejection (see [PinningFailureHeuristic]) -- see [PinningFailureTracker], which decides
     *  whether repeated occurrences for the same app warrant auto-exempting it. */
    private val onSuspectedPinningFailure: (Int) -> Unit = {},
) {
    private data class FlowKey(val srcIp: String, val srcPort: Int, val dstIp: String, val dstPort: Int)

    private class Connection(val key: FlowKey, val templatePacket: IpPacket) {
        val inbox = Channel<IpPacket>(Channel.UNLIMITED)

        @Volatile var socket: Socket? = null

        // Set for proxied connections only (see performHttpConnect) -- relayFromSocket reads from
        // this same buffered stream instead of a fresh socket.getInputStream(), so any bytes the
        // CONNECT-response line reader already buffered-but-didn't-consume (the client's own TLS
        // ClientHello, already pipelined onto the wire right after the CONNECT request) are still
        // correctly delivered to the relay, never dropped.
        @Volatile var socketInput: InputStream? = null

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
        @Volatile var clientWindow: Long = WINDOW_SIZE.toLong()

        // Non-null only if *both* sides negotiated window scaling on the SYN/SYN-ACK (RFC 1323:
        // if either side omits the option, neither may use it). [peerShift] is the client's own
        // announced shift, needed to decode the window field of every ACK it sends us from then
        // on; [ourShift] is the shift we announced, needed to correctly encode our own advertised
        // window (otherwise stuck at 65535 like the client would be without this at all).
        @Volatile var peerShift: Int? = null
        @Volatile var ourShift: Int? = null

        // Debug-only bookkeeping to measure real relay throughput/stall time per connection.
        @Volatile var bytesFromSocket: Long = 0
        @Volatile var windowWaitMillis: Long = 0
        @Volatile var socketReadMillis: Long = 0
        @Volatile var tunWriteMillis: Long = 0
        @Volatile var readCount: Int = 0
        val startedAtMillis: Long = System.currentTimeMillis()

        // Set once in establish(), read in closeConnection() to decide whether this connection is
        // even eligible for the pinning-failure heuristic -- only meaningful for flows that were
        // actually sent through mitmproxy, attributed to the app whose UID owns the flow.
        @Volatile var wasProxied: Boolean = false
        @Volatile var ownerUidForBlame: Int? = null

        // Guards [closeConnection] against running twice for the same connection -- the socket
        // read loop and the tun-side inbox consumer run on independent coroutines and can both
        // hit an error (e.g. one closes the socket, which makes the other's next read/write throw)
        // at nearly the same time, each independently deciding to close+RST.
        val closed = AtomicBoolean(false)
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
                existing.inbox.close()
                // Routed through closeConnection (not just removed here) so the pinning-failure
                // heuristic sees client-initiated resets too -- a genuine pinning rejection often
                // surfaces as exactly this, the client aborting the moment it dislikes our
                // substitute certificate, not a clean EOF on the socket side.
                scope.launch { closeConnection(existing, sendRst = false) }
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
        // Whether this flow is even eligible for the proxy at all, ignoring exemption -- only
        // 80/443 go through the filter proxy; everything else (chat/game/VoIP ports, etc.) keeps
        // relaying directly, unchanged, exactly as if the proxy didn't exist.
        val proxyEligible = proxyConfig.enabled &&
            (connection.key.dstPort == HTTP_PORT || connection.key.dstPort == HTTPS_PORT) &&
            !isFilterHostDestination(connection.key.dstIp)
        // Resolved whenever the flow is proxy-eligible, not just once some app is already
        // exempted -- needed both for the exemption check itself and so a *newly* misbehaving app
        // (not yet on anyone's list) can still be attributed correctly if PinningFailureTracker
        // ends up flagging it once this connection closes.
        val ownerUid = if (proxyEligible) {
            resolveOwnerUid(connection.key.srcIp, connection.key.srcPort, connection.key.dstIp, connection.key.dstPort)
        } else {
            null
        }
        val mitmExempt = proxyEligible && MitmExemptionPolicy.isExempt(
            ownerUid,
            mitmExemptUids,
            resolveHostname(connection.key.dstIp),
            mitmExemptHostSuffixes,
        )
        if (mitmExempt) {
            Log.d(TAG, "${connection.key.dstIp}:${connection.key.dstPort} MITM-exempt (uid=$ownerUid) -- connecting directly")
        }
        // A MITM-exempt flow (certificate-pinned app) skips the proxy, but -- unlike a full
        // VpnService-level bypass -- stays inside the tunnel: its DNS is still filtered and
        // QUIC/443-UDP is still dropped for it below, same as every other captured flow.
        val useProxy = proxyEligible && !mitmExempt
        connection.wasProxied = useProxy
        connection.ownerUidForBlame = ownerUid

        val socket = Socket()
        var connectInputStream: BufferedInputStream? = null
        val connected = try {
            // A freshly-constructed Socket has no underlying file descriptor until it's bound (or
            // connected) -- protect() silently fails on it otherwise, since there's nothing to mark.
            socket.bind(InetSocketAddress(0))
            if (!protect(socket)) throw IOException("VpnService.protect() failed")
            // Blocking, but this whole call chain already runs on [scope]'s dispatcher -- a
            // dedicated unbounded pool sized for one thread per concurrent connection (see
            // VpnFilterService), not the global Dispatchers.IO, so it's safe to block here
            // directly without an extra withContext hop.
            if (useProxy) {
                // Prefer IPv4 when the filter hostname has a stale/unused AAAA (Android often
                // tries IPv6 first and the CONNECT never reaches the proxy).
                val proxyAddr = InetAddress.getAllByName(proxyConfig.host)
                    .firstOrNull { it is Inet4Address }
                    ?: InetAddress.getByName(proxyConfig.host)
                socket.connect(InetSocketAddress(proxyAddr, proxyConfig.port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                // Prefer a real hostname (from a DNS answer this device itself already saw) over
                // the bare destination IP on the CONNECT line -- purely cosmetic/best-effort: the
                // proxy determines the actual destination independently from the tunneled TLS
                // ClientHello SNI / plaintext HTTP Host header either way, so a cache miss here
                // (falling back to the IP) doesn't weaken filtering at all.
                val targetHost = resolveHostname(connection.key.dstIp) ?: connection.key.dstIp
                connectInputStream = performHttpConnect(socket, targetHost, connection.key.dstPort)
            } else {
                socket.connect(InetSocketAddress(InetAddress.getByName(connection.key.dstIp), connection.key.dstPort), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
            }
            true
        } catch (error: Exception) {
            Log.w(
                TAG,
                "${if (useProxy) "Proxy CONNECT via ${proxyConfig.host}:${proxyConfig.port} to" else "TCP connect to"} " +
                    "${connection.key.dstIp}:${connection.key.dstPort} failed",
                error,
            )
            false
        }
        if (!connected) {
            // Fail closed for 80/443 when the proxy is enabled: no fallback to a direct connection
            // on proxy failure, by construction -- there simply is no direct-connect code path
            // taken here once useProxy is true, only the RST below.
            connections.remove(connection.key)
            runCatching { socket.close() }
            writeToTun(rstFor(synPacket))
            return
        }

        // The client may have already aborted (RST) while `connect()` above was blocking --
        // `handle()` removes the connection from `connections` and closes its inbox as soon as
        // that happens, but at that point there was no socket yet for it to close. Without this
        // check, a connect() that happens to succeed just after the client gave up would still
        // send a SYN-ACK the client never asked for anymore, then spin up `relayFromSocket`
        // against a real, fully-connected destination socket that nothing will ever close again
        // (a repeat RST from the client just gets ignored, since this key is no longer registered)
        // -- a genuine leaked socket + coroutine for the lifetime of that remote connection.
        if (connections[connection.key] !== connection) {
            runCatching { socket.close() }
            return
        }

        connection.socket = socket
        connection.socketInput = connectInputStream
        connection.clientNextSeq = (synPacket.tcpSeq + 1) and SEQ_MASK
        val isn = Random.nextInt().toLong() and SEQ_MASK
        connection.serverSeq = (isn + 1) and SEQ_MASK
        // Anchor the "acked" edge to the same point so the first waitForWindow() check (which
        // may run before the client's handshake-completing ACK has been processed) doesn't see a
        // bogus multi-gigabyte "in-flight" gap between a real serverSeq and a zeroed clientAcked.
        connection.clientAcked = connection.serverSeq
        // Only offer scaling back if the client offered it first -- replying with the option when
        // the client didn't send one is meaningless (nothing says the client would honor it), and
        // omitting it when the client did would leave scaling off entirely per RFC 1323.
        connection.peerShift = synPacket.tcpWindowScale
        connection.ourShift = synPacket.tcpWindowScale?.let { OUR_WINDOW_SHIFT }
        Log.d(
            TAG,
            "${connection.key.dstIp}:${connection.key.dstPort} window scale: client offered=${synPacket.tcpWindowScale}, negotiated=${connection.ourShift != null}",
        )
        writeToTun(
            synPacket.buildTcpSegment(
                isn,
                connection.clientNextSeq,
                IpPacket.TCP_SYN or IpPacket.TCP_ACK,
                encodedWindow(connection),
                windowScaleToAdvertise = connection.ourShift,
            ),
        )

        scope.launch { consumeInbox(connection) }
        scope.launch { relayFromSocket(connection) }
    }

    /** The window field value to put on the wire for [connection]'s current advertised receive
     * window: scaled down by [Connection.ourShift] if negotiated, otherwise the same flat
     * [WINDOW_SIZE] this relay always used before scaling support existed. */
    private fun encodedWindow(connection: Connection): Int {
        val shift = connection.ourShift ?: return WINDOW_SIZE
        return (DESIRED_RECEIVE_WINDOW shr shift).coerceIn(0, 0xFFFF).toInt()
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
            connection.clientWindow = packet.tcpWindow.toLong() shl (connection.peerShift ?: 0)
        }
        if (packet.payload.isNotEmpty() && packet.tcpSeq == connection.clientNextSeq) {
            try {
                socket.getOutputStream().write(packet.payload)
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
            writeToTun(packet.buildTcpSegment(connection.serverSeq, ackValue, IpPacket.TCP_ACK, encodedWindow(connection)))
        }
    }

    /**
     * Blocks (without holding up any other connection -- each runs in its own coroutine) until
     * the client's advertised window has room for [neededBytes] more unacknowledged bytes, per
     * its most recent ACK. Polls rather than using a signal, since a window update is just an
     * ordinary incoming ACK processed on a different coroutine ([consumeInbox]) with no natural
     * place to hang a callback; the poll interval is short enough not to add noticeable latency.
     *
     * Bounded by [WINDOW_STALL_TIMEOUT_MS]: if the client's TCP stack never reopens the window
     * (backgrounded/killed app, network drop, or any other reason it stops ACKing), this used to
     * spin every [WINDOW_POLL_DELAY_MS] forever -- one connection stuck like this is a rounding
     * error, but stuck connections accumulate over a long-running VPN session (normal phone usage
     * opens hundreds of TCP flows a day), and each one burning CPU nonstop is exactly what turns
     * into severe battery drain over hours. Returns false instead of hanging so the caller can
     * give up on the connection.
     */
    private suspend fun waitForWindow(connection: Connection, neededBytes: Int): Boolean {
        val waitStart = System.currentTimeMillis()
        while (true) {
            val inFlight = (connection.serverSeq - connection.clientAcked) and SEQ_MASK
            val available = connection.clientWindow - inFlight
            if (available >= neededBytes) {
                connection.windowWaitMillis += System.currentTimeMillis() - waitStart
                return true
            }
            if (System.currentTimeMillis() - waitStart > WINDOW_STALL_TIMEOUT_MS) return false
            delay(WINDOW_POLL_DELAY_MS)
        }
    }

    private suspend fun relayFromSocket(connection: Connection) {
        val socket = connection.socket ?: return
        val buffer = ByteArray(MAX_SEGMENT_SIZE)
        try {
            // Proxied connections must keep reading from the same buffered stream
            // performHttpConnect used for the CONNECT response -- a fresh socket.getInputStream()
            // here would silently drop whatever that stream's buffer already read ahead from the
            // socket but hadn't handed back yet (the client's own pipelined TLS bytes). Direct
            // (non-proxied) connections never had a stream created, so they fall back to a plain
            // one here, same as before this optimization.
            val input = connection.socketInput ?: socket.getInputStream()
            while (true) {
                val readStart = System.currentTimeMillis()
                val read = input.read(buffer)
                connection.socketReadMillis += System.currentTimeMillis() - readStart
                if (read < 0) break
                connection.bytesFromSocket += read
                connection.readCount++
                if (!waitForWindow(connection, read)) {
                    Log.w(
                        TAG,
                        "${connection.key.dstIp}:${connection.key.dstPort} window never reopened after " +
                            "${WINDOW_STALL_TIMEOUT_MS}ms -- abandoning stalled connection",
                    )
                    closeConnection(connection, sendRst = true)
                    return
                }
                val writeStart = System.currentTimeMillis()
                writeToTun(
                    connection.templatePacket.buildTcpSegment(
                        connection.serverSeq,
                        connection.clientNextSeq,
                        IpPacket.TCP_ACK or IpPacket.TCP_PSH,
                        encodedWindow(connection),
                        buffer.copyOf(read),
                    ),
                )
                connection.tunWriteMillis += System.currentTimeMillis() - writeStart
                connection.serverSeq = (connection.serverSeq + read) and SEQ_MASK
            }
            writeToTun(
                connection.templatePacket.buildTcpSegment(
                    connection.serverSeq,
                    connection.clientNextSeq,
                    IpPacket.TCP_FIN or IpPacket.TCP_ACK,
                    encodedWindow(connection),
                ),
            )
            connection.serverSeq = (connection.serverSeq + 1) and SEQ_MASK
            closeConnection(connection, sendRst = false)
        } catch (error: IOException) {
            closeConnection(connection, sendRst = true)
        }
    }

    private suspend fun closeConnection(connection: Connection, sendRst: Boolean) {
        if (!connection.closed.compareAndSet(false, true)) return
        connections.remove(connection.key)
        connection.inbox.close()
        val elapsedMs = System.currentTimeMillis() - connection.startedAtMillis
        if (connection.bytesFromSocket > 0) {
            val mbps = if (elapsedMs > 0) (connection.bytesFromSocket * 8.0 / 1000.0 / elapsedMs) else 0.0
            Log.d(
                TAG,
                "${connection.key.dstIp}:${connection.key.dstPort} closed: ${connection.bytesFromSocket}B in ${elapsedMs}ms " +
                    "(%.1fMbps), reads=${connection.readCount}, socket-read=${connection.socketReadMillis}ms, ".format(mbps) +
                    "tun-write=${connection.tunWriteMillis}ms, window-wait=${connection.windowWaitMillis}ms, shift=${connection.ourShift}",
            )
        }
        val uid = connection.ownerUidForBlame
        if (connection.wasProxied && uid != null &&
            PinningFailureHeuristic.looksLikeRejection(elapsedMs, connection.bytesFromSocket, connection.readCount)
        ) {
            Log.d(TAG, "${connection.key.dstIp}:${connection.key.dstPort} looks like a pinning rejection (uid=$uid)")
            onSuspectedPinningFailure(uid)
        }
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

    /**
     * Performs the HTTP CONNECT handshake against an already-connected proxy socket. Throws
     * (causing the caller to fail the flow closed) unless the proxy replies with a 2xx status --
     * after that, the socket is a raw, opaque tunnel to the real destination and the existing
     * relay loops below take over untouched, exactly as if this had connected directly.
     *
     * Returns the [BufferedInputStream] used to read the response, which the caller must keep
     * using for the rest of this connection's lifetime (see [Connection.socketInput]) -- a fresh
     * unbuffered `socket.getInputStream()` call afterward would skip whatever this stream's
     * internal buffer already read ahead from the socket but never handed back (some of the
     * client's own pipelined TLS bytes, not just the CONNECT response itself).
     */
    private fun performHttpConnect(socket: Socket, targetHost: String, targetPort: Int): BufferedInputStream {
        val credentials = Base64.getEncoder().encodeToString("${proxyConfig.user}:${proxyConfig.password}".toByteArray())
        val request = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
            "Host: $targetHost:$targetPort\r\n" +
            "Proxy-Authorization: Basic $credentials\r\n" +
            "Proxy-Connection: Keep-Alive\r\n" +
            "\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()

        // Buffered (unlike a raw socket read) so parsing the response's several short lines costs
        // one or two real syscalls total instead of one per byte -- still stops at exactly the
        // right place (readOneHttpLine only ever consumes up to and including the blank line), so
        // nothing past the response is lost; any bytes this buffer read ahead but didn't hand back
        // stay available to whoever reads from this same stream next (relayFromSocket).
        val input = BufferedInputStream(socket.getInputStream(), CONNECT_RESPONSE_BUFFER_SIZE)
        val statusLine = readOneHttpLine(input) ?: throw IOException("Proxy CONNECT: no response")
        if (!Regex("""HTTP/1\.[01] 2\d\d""").containsMatchIn(statusLine)) {
            throw IOException("Proxy CONNECT to $targetHost:$targetPort failed: $statusLine")
        }
        while (true) {
            val line = readOneHttpLine(input) ?: throw IOException("Proxy CONNECT: truncated response headers")
            if (line.isEmpty()) break
        }
        return input
    }

    private fun readOneHttpLine(input: InputStream): String? {
        val line = StringBuilder()
        var previousWasCr = false
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            val char = byte.toChar()
            if (previousWasCr && char == '\n') {
                line.setLength(line.length - 1) // drop the CR already appended below
                return line.toString()
            }
            line.append(char)
            previousWasCr = char == '\r'
        }
    }

    /** RST reply for a SYN we're refusing (blocked destination or failed connect) or a stray
     * non-SYN packet with no known connection -- built straight from the wire packet, not any
     * tracked connection state, since none exists yet. */
    private fun rstFor(packet: IpPacket): ByteArray {
        val ackValue = (packet.tcpSeq + maxOf(packet.payload.size, if (packet.isSyn) 1 else 0)) and SEQ_MASK
        return packet.buildTcpSegment(seq = 0, ack = ackValue, flags = IpPacket.TCP_RST or IpPacket.TCP_ACK, window = 0)
    }

    /**
     * Resolved once per relay instance (a fresh [TcpRelayManager] is created on every tunnel
     * reestablish(), which is exactly when a changed proxy host should take effect anyway) instead
     * of a fresh DNS lookup on every single new TCP connection -- that per-connection resolution
     * added real, avoidable latency to every new flow while the proxy is enabled, for a value that
     * never changes across this instance's lifetime. Trade-off: if the proxy host's IP changes
     * (e.g. a home broadband lease renewal) mid-session without a reestablish(), this stays stale
     * until the next one -- rare in practice, and the failure mode is just reverting to the
     * pre-optimization behavior (this destination briefly treated as proxy-eligible again), not a
     * filtering bypass.
     */
    private val filterHostAddresses: Set<String> by lazy {
        val host = proxyConfig.host.trim()
        if (host.isEmpty()) {
            emptySet()
        } else {
            try {
                InetAddress.getAllByName(host).mapNotNullTo(mutableSetOf()) { it.hostAddress }
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    /**
     * Don't MITM the family's own filter/update host: CONNECT-through-mitmproxy to
     * vpn.bartholomew.help (same box) hairpins TLS and breaks App updates / Caddy fetches with
     * TLSV1_ALERT_INTERNAL_ERROR. Relay those destinations directly (still protect()'d).
     */
    private fun isFilterHostDestination(dstIp: String): Boolean {
        val host = proxyConfig.host.trim()
        if (host.isEmpty()) return false
        if (dstIp.equals(host, ignoreCase = true)) return true
        return dstIp in filterHostAddresses
    }

    private companion object {
        const val TAG = "TcpRelayManager"
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        const val WINDOW_SIZE = 65535
        // 7 => a raw window field of up to 65535 represents up to ~8.4MB real bytes, comfortably
        // covering the bandwidth-delay product of a fast (100s of Mbps) link even at 100+ms RTT.
        const val OUR_WINDOW_SHIFT = 7
        const val DESIRED_RECEIVE_WINDOW = 4L * 1024 * 1024
        // Matches VpnFilterService's tun MTU (16384) minus IP/TCP header overhead, with some
        // margin -- see that MTU constant's comment for why this can be so much larger than a
        // "real" 1500-byte Ethernet MTU.
        const val MAX_SEGMENT_SIZE = 16 * 1024 - 100
        const val CONNECT_TIMEOUT_MS = 8_000
        // The CONNECT response is just a status line + a handful of short headers -- this only
        // needs to be big enough to avoid multiple refill syscalls for that, not sized for payload.
        const val CONNECT_RESPONSE_BUFFER_SIZE = 512
        const val SEQ_MASK = 0xFFFFFFFFL
        const val WRAP_THRESHOLD = 0x80000000L
        const val WINDOW_POLL_DELAY_MS = 10L
        const val WINDOW_STALL_TIMEOUT_MS = 30_000L
    }
}
