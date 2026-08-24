package app.otterling.content

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the CONNECT-response buffering optimization: a mock proxy deliberately
 * writes the CONNECT response and extra "destination" bytes in a single socket write, simulating
 * mitmproxy forwarding the first bytes of the real destination's reply in the same TCP segment as
 * the CONNECT response itself. Before this optimization, relayFromSocket called a fresh
 * socket.getInputStream() after performHttpConnect returned, silently losing whatever that
 * buffered read had already consumed past the response. This test invokes the real (private)
 * performHttpConnect via reflection -- not a reimplementation -- so it fails if that guarantee
 * ever regresses.
 */
class TcpRelayManagerConnectBufferingTest {
    @Test
    fun `performHttpConnect preserves pipelined bytes past the CONNECT response`() {
        val server = ServerSocket(0)
        val pipelinedPayload = "PIPELINED_DESTINATION_BYTES".toByteArray(Charsets.US_ASCII)

        val serverThread = Thread {
            server.accept().use { client ->
                // Drain the CONNECT request headers (up to the blank line) before replying, same
                // as a real proxy would.
                val input = client.getInputStream()
                val seen = StringBuilder()
                while (!seen.endsWith("\r\n\r\n")) {
                    seen.append(input.read().toChar())
                }
                // Single write: response + the "destination"'s own first bytes, both in one
                // segment -- this is the exact scenario the buffering fix guards against.
                val response = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII) + pipelinedPayload
                client.getOutputStream().write(response)
                client.getOutputStream().flush()
            }
        }
        serverThread.start()

        val relay = TcpRelayManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            protect = { true },
            writeToTun = {},
            isBlockedDestination = { _, _ -> false },
            proxyConfig = ProxyConfig(enabled = true, host = "127.0.0.1", port = server.localPort, user = "u", password = "p"),
            resolveHostname = { null },
        )

        val socket = Socket("127.0.0.1", server.localPort)
        val method = TcpRelayManager::class.java.getDeclaredMethod(
            "performHttpConnect",
            Socket::class.java,
            String::class.java,
            Int::class.java,
        )
        method.isAccessible = true
        val bufferedStream = method.invoke(relay, socket, "example.com", 443) as BufferedInputStream

        val received = ByteArray(pipelinedPayload.size)
        var read = 0
        while (read < received.size) {
            val n = bufferedStream.read(received, read, received.size - read)
            check(n >= 0) { "stream closed early" }
            read += n
        }

        assertEquals(String(pipelinedPayload, Charsets.US_ASCII), String(received, Charsets.US_ASCII))

        socket.close()
        server.close()
        serverThread.join(2_000)
    }

    /** Regression test for the cached-DNS-lookup optimization: [TcpRelayManager] resolves the
     *  filter host's addresses lazily once per instance, not on every call -- this checks the
     *  cache actually matches both the literal hostname and its resolved IP, and rejects an
     *  unrelated IP, via the real private isFilterHostDestination method. */
    @Test
    fun `isFilterHostDestination matches the configured host by name and resolved IP`() {
        val relay = TcpRelayManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            protect = { true },
            writeToTun = {},
            isBlockedDestination = { _, _ -> false },
            proxyConfig = ProxyConfig(enabled = true, host = "localhost", port = 8090, user = "u", password = "p"),
            resolveHostname = { null },
        )
        val method = TcpRelayManager::class.java.getDeclaredMethod("isFilterHostDestination", String::class.java)
        method.isAccessible = true

        assertTrue(method.invoke(relay, "localhost") as Boolean)
        assertTrue(method.invoke(relay, "127.0.0.1") as Boolean)
        assertFalse(method.invoke(relay, "8.8.8.8") as Boolean)
    }

    /**
     * Regression test for the transient-blip retry (see TcpRelayManager.establish()/attemptConnect):
     * a proxy CONNECT that fails once (nothing listening yet) should be retried once, and succeed
     * if the proxy becomes reachable again within RETRY_DELAY_MS -- the family shouldn't see a
     * broken page for a blip that clears within a fraction of a second. Goes through the real,
     * public handle() entry point (not reflection) with a hand-built SYN packet, since that's the
     * only way to exercise establish()'s actual retry orchestration end to end.
     */
    @Test
    fun `establish retries a failed proxy CONNECT once and succeeds if the proxy recovers in time`() {
        val reservation = ServerSocket(0)
        val port = reservation.localPort
        reservation.close() // freed immediately -- reserved only to know the port number up front

        val serverThread = Thread {
            // Simulates a brief proxy blip: nothing listens on `port` for the first ~80ms (well
            // under the relay's 250ms retry delay), then the proxy "comes back".
            Thread.sleep(80)
            ServerSocket(port).use { server ->
                server.accept().use { client ->
                    val input = client.getInputStream()
                    val seen = StringBuilder()
                    while (!seen.endsWith("\r\n\r\n")) {
                        seen.append(input.read().toChar())
                    }
                    client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().flush()
                    Thread.sleep(500) // keep the socket open long enough for the assertions below
                }
            }
        }
        serverThread.start()

        val writes = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val pinningFailures = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val proxyOutageFailures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val relay = TcpRelayManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            protect = { true },
            writeToTun = { writes.add(it) },
            isBlockedDestination = { _, _ -> false },
            proxyConfig = ProxyConfig(enabled = true, host = "127.0.0.1", port = port, user = "u", password = "p"),
            resolveHostname = { null },
            onSuspectedPinningFailure = { pinningFailures.add(it) },
            onProxyConnectFailure = { proxyOutageFailures.add(it) },
        )

        relay.handle(buildSynPacket(srcIp = "10.111.222.5", srcPort = 54321, dstIp = "203.0.113.10", dstPort = 443))

        waitUntil { writes.isNotEmpty() }
        assertEquals(1, writes.size)
        val reply = IpPacket.parse(writes[0], writes[0].size)!!
        assertTrue("expected a SYN-ACK (retry succeeded), got flags=${reply.tcpFlags}", reply.isSyn && reply.isAck && !reply.isRst)
        assertTrue("a recovered blip must not report a proxy outage", proxyOutageFailures.isEmpty())
        assertTrue(pinningFailures.isEmpty())

        serverThread.join(2_000)
    }

    /**
     * Regression test for fail-closed being preserved by the retry: a proxy that's down for both
     * the original attempt AND the retry must still RST the flow -- never fall back to a direct
     * (unproxied) connection -- and this connect-failure path must never double-trigger
     * PinningFailureTracker's callback, which only ever fires for a connection that actually
     * connected and was then rejected mid-handshake (see TcpRelayManager.closeConnection). It
     * should, however, report the failure via onProxyConnectFailure, so a sustained outage is
     * distinguishable from one app's certificate pinning.
     */
    @Test
    fun `establish fails closed after both proxy attempts fail, without triggering the pinning callback`() {
        val reservation = ServerSocket(0)
        val deadPort = reservation.localPort
        reservation.close() // freed and never reopened -- both attempts hit connection-refused

        val writes = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val pinningFailures = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val proxyOutageFailures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val relay = TcpRelayManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            protect = { true },
            writeToTun = { writes.add(it) },
            isBlockedDestination = { _, _ -> false },
            proxyConfig = ProxyConfig(enabled = true, host = "127.0.0.1", port = deadPort, user = "u", password = "p"),
            resolveHostname = { null },
            onSuspectedPinningFailure = { pinningFailures.add(it) },
            onProxyConnectFailure = { proxyOutageFailures.add(it) },
        )

        relay.handle(buildSynPacket(srcIp = "10.111.222.5", srcPort = 54322, dstIp = "203.0.113.11", dstPort = 443))

        waitUntil { writes.isNotEmpty() }
        assertEquals(1, writes.size)
        val reply = IpPacket.parse(writes[0], writes[0].size)!!
        assertTrue("a sustained proxy failure must still fail closed with an RST", reply.isRst)
        assertEquals(listOf("203.0.113.11"), proxyOutageFailures)
        assertTrue("a connect failure must never trigger the pinning-rejection callback", pinningFailures.isEmpty())
    }

    private fun waitUntil(timeoutMs: Long = 3_000, intervalMs: Long = 20, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        check(condition()) { "condition not met within ${timeoutMs}ms" }
    }

    /**
     * Hand-builds a minimal IPv4/TCP SYN segment -- IpPacket has no public constructor (only
     * [IpPacket.parse] and the build*Reply methods, which need an existing packet to reply to), and
     * [IpPacket.parse] doesn't validate checksums, so a zeroed checksum field is fine for a test.
     */
    private fun buildSynPacket(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, seq: Long = 1_000L): IpPacket {
        val ipHeaderSize = 20
        val tcpHeaderSize = 20
        val buffer = ByteArray(ipHeaderSize + tcpHeaderSize)
        buffer[0] = 0x45 // version 4, IHL 5 (20 bytes, no options)
        buffer[2] = ((buffer.size shr 8) and 0xFF).toByte()
        buffer[3] = (buffer.size and 0xFF).toByte()
        buffer[8] = 64 // TTL
        buffer[9] = 6 // protocol: TCP
        writeAddressBytes(buffer, 12, srcIp)
        writeAddressBytes(buffer, 16, dstIp)

        val tcpOffset = ipHeaderSize
        buffer[tcpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        buffer[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        buffer[tcpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        buffer[tcpOffset + 3] = (dstPort and 0xFF).toByte()
        buffer[tcpOffset + 4] = ((seq shr 24) and 0xFF).toByte()
        buffer[tcpOffset + 5] = ((seq shr 16) and 0xFF).toByte()
        buffer[tcpOffset + 6] = ((seq shr 8) and 0xFF).toByte()
        buffer[tcpOffset + 7] = (seq and 0xFF).toByte()
        buffer[tcpOffset + 12] = (5 shl 4).toByte() // data offset: 5 * 4 = 20 bytes, no options
        buffer[tcpOffset + 13] = 0x02 // SYN
        buffer[tcpOffset + 14] = 0xFF.toByte()
        buffer[tcpOffset + 15] = 0xFF.toByte()
        return IpPacket.parse(buffer, buffer.size)!!
    }

    private fun writeAddressBytes(buffer: ByteArray, offset: Int, address: String) {
        address.split(".").forEachIndexed { i, part -> buffer[offset + i] = part.toInt().toByte() }
    }

    /**
     * Manual, opt-in check against the real live filter server -- skipped unless PROXY_PW is set
     * (never committed, never run in normal test runs), since it needs real network access and a
     * real secret. Confirms the actual shipped performHttpConnect gets a real 2xx from mitmproxy
     * with real credentials, i.e. the optimized code path still works end-to-end against
     * production, not just against the local mock server above.
     */
    @Test
    fun `manual -- real proxy CONNECT succeeds with real credentials`() {
        val password = System.getenv("PROXY_PW")
        org.junit.Assume.assumeTrue("Skipped unless PROXY_PW is set", !password.isNullOrEmpty())

        val relay = TcpRelayManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            protect = { true },
            writeToTun = {},
            isBlockedDestination = { _, _ -> false },
            proxyConfig = ProxyConfig(enabled = true, host = "vpn.bartholomew.help", port = 8090, user = "otterling", password = password!!),
            resolveHostname = { null },
        )
        val method = TcpRelayManager::class.java.getDeclaredMethod(
            "performHttpConnect",
            Socket::class.java,
            String::class.java,
            Int::class.java,
        )
        method.isAccessible = true
        val socket = Socket("vpn.bartholomew.help", 8090)
        try {
            method.invoke(relay, socket, "example.com", 443) // throws (wrapped) if not a 2xx
        } finally {
            socket.close()
        }
    }
}
