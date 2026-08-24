package app.otterling.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinningFailureHeuristicTest {
    @Test
    fun matchesRealCapturedRejectionShapes() {
        // Real values captured from app.morphe.android.youtube being MITM'd before exemption.
        assertTrue(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 236, bytesFromPeer = 2981, readCount = 2))
        assertTrue(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 256, bytesFromPeer = 2560, readCount = 2))
        assertTrue(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 677, bytesFromPeer = 6358, readCount = 3))
    }

    @Test
    fun doesNotMatchRealCapturedLongRunningSuccessShape() {
        // Real value captured from the same app once exempted: a real, longer-lived data
        // transfer. (Exempted/direct-connect flows never reach this check in production at all --
        // TcpRelayManager only evaluates it for wasProxied=true connections -- but the shape still
        // shouldn't false-positive if it were ever compared.)
        assertFalse(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 10461, bytesFromPeer = 5342, readCount = 2))
    }

    @Test
    fun tooFewBytesIsNotARejection() {
        // Near-instant, near-empty close is a refused/unreachable destination, not a pinning
        // rejection -- a different failure mode that must not trigger the same response.
        assertFalse(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 5, bytesFromPeer = 0, readCount = 0))
        assertFalse(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 5, bytesFromPeer = 10, readCount = 1))
    }

    @Test
    fun tooManyBytesIsNotARejection() {
        // A real handshake-plus-partial-response is bigger than the bare handshake this targets.
        assertFalse(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 500, bytesFromPeer = 50_000, readCount = 3))
    }

    @Test
    fun tooLongElapsedIsNotARejection() {
        // 5_000ms is MAX_ELAPSED_MS itself -- an inclusive boundary that legitimately matches (see
        // matchesRealCapturedRejectionShapes) -- so "too long" needs a value past it, not at it.
        assertFalse(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 5_001, bytesFromPeer = 3_000, readCount = 2))
    }

    @Test
    fun tooManyReadsIsNotARejection() {
        assertFalse(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 500, bytesFromPeer = 3_000, readCount = 10))
    }

    @Test
    fun zeroElapsedIsNotARejection() {
        // elapsedMs=0 would be a suspicious/degenerate measurement, not a real observation.
        assertFalse(PinningFailureHeuristic.looksLikeRejection(elapsedMs = 0, bytesFromPeer = 3_000, readCount = 2))
    }
}
