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
            isBlockedDestination = { false },
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
            isBlockedDestination = { false },
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
            isBlockedDestination = { false },
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
