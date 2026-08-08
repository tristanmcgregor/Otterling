package app.otterling.content

/**
 * Decides whether a just-closed *proxied* TCP connection's shape looks like a certificate-pinning
 * rejection rather than an ordinary short-but-successful request or an unrelated failure.
 *
 * The signal: when a pinned app's flow goes through mitmproxy, mitmproxy hands it a substitute
 * certificate, the client's TLS stack rejects it, and the client aborts within moments -- having
 * exchanged only the handshake bytes (ServerHello + Certificate, roughly a few KB), never a real
 * request/response cycle. Confirmed against real captures from `app.morphe.android.youtube` being
 * MITM'd before it was exempted: failures closed in 216-677ms after exchanging 2560-6358 bytes
 * over 2-3 reads, versus working (exempted) connections lasting 10+ seconds with real data flow.
 *
 * Deliberately requires a *minimum* byte count too, not just "fast and small" -- an unreachable
 * destination or a refused connection closes near-instantly with near-zero bytes, a different
 * failure mode (server/network down, not a pinning rejection) that needs a different fix and must
 * not be treated the same way here.
 *
 * A single match is not proof by itself (a legitimately short, successful request could
 * occasionally look similar) -- callers should require several matches for the same app before
 * acting; see [PinningFailureTracker].
 */
object PinningFailureHeuristic {
    fun looksLikeRejection(elapsedMs: Long, bytesFromPeer: Long, readCount: Int): Boolean =
        elapsedMs in 1..MAX_ELAPSED_MS &&
            bytesFromPeer in MIN_BYTES..MAX_BYTES &&
            readCount in 1..MAX_READS

    private const val MAX_ELAPSED_MS = 3_000L
    private const val MIN_BYTES = 500L
    private const val MAX_BYTES = 20_000L
    private const val MAX_READS = 4
}
