package app.otterling.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyOutageTrackerTest {
    @Test
    fun `fewer than threshold distinct destinations does not fire`() {
        val tracker = ProxyOutageTracker()
        repeat(ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1) { i ->
            assertFalse(tracker.recordFailure("203.0.113.$i"))
        }
    }

    @Test
    fun `threshold distinct destinations fires exactly once, not again at the same count`() {
        val tracker = ProxyOutageTracker()
        repeat(ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1) { i ->
            assertFalse(tracker.recordFailure("203.0.113.$i"))
        }
        // The call that crosses the threshold fires...
        assertTrue(tracker.recordFailure("203.0.113.${ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1}"))
        // ...but a further failure while still above threshold (even a brand new destination)
        // must not fire again -- AlertReporter's own debounce is the belt-and-suspenders backup,
        // not the only thing preventing an alert flood.
        assertFalse(tracker.recordFailure("203.0.113.99"))
    }

    @Test
    fun `repeated failures for the same destination never count as distinct`() {
        val tracker = ProxyOutageTracker()
        // One genuinely-down site retried many times must never look like an outage across many
        // destinations -- that's a per-app/single-destination problem, not a proxy-health one.
        repeat(50) {
            assertFalse(tracker.recordFailure("203.0.113.42"))
        }
    }

    @Test
    fun `entries older than the outage window are pruned and do not count toward the threshold`() {
        var now = 0L
        val tracker = ProxyOutageTracker(nowProvider = { now })

        // Two failures right at the start of the window...
        assertFalse(tracker.recordFailure("203.0.113.1"))
        assertFalse(tracker.recordFailure("203.0.113.2"))

        // ...age them out entirely...
        now += ProxyOutageTracker.OUTAGE_WINDOW_MS + 1

        // ...so even after enough *new* failures to reach the threshold count textually, the aged-out
        // ones must not still be silently included -- only the fresh ones within the current window
        // should count, meaning this needs a full THRESHOLD fresh failures to fire, not just
        // (threshold - 2) more piled on top of the stale ones.
        repeat(ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1) { i ->
            assertFalse(tracker.recordFailure("203.0.113.${10 + i}"))
        }
        assertTrue(tracker.recordFailure("203.0.113.${10 + ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1}"))
    }

    @Test
    fun `a fresh outage can re-fire after the count drops back under threshold`() {
        var now = 0L
        val tracker = ProxyOutageTracker(nowProvider = { now })

        // First outage: reaches threshold, fires once.
        repeat(ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1) { i ->
            tracker.recordFailure("203.0.113.$i")
        }
        assertTrue(tracker.recordFailure("203.0.113.${ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1}"))

        // Recovery: let the whole first outage age out of the window (count drops back to zero).
        now += ProxyOutageTracker.OUTAGE_WINDOW_MS + 1

        // A brand new outage should be able to fire again -- not stay silenced forever after the
        // first alert, since a real outage can recur after a genuine recovery.
        repeat(ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1) { i ->
            assertFalse(tracker.recordFailure("198.51.100.$i"))
        }
        assertTrue(tracker.recordFailure("198.51.100.${ProxyOutageTracker.OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD - 1}"))
    }
}
