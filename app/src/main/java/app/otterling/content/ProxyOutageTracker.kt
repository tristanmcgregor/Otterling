package app.otterling.content

/**
 * Distinguishes "the filter proxy itself looks unreachable" from "one specific app is
 * certificate-pinned" -- the latter is [PinningFailureTracker]'s job (a single app's flows
 * repeatedly rejected right after the TLS handshake, corroborated over up to 24h, persisted across
 * tunnel generations because it's a slow-accumulating per-app signal). A proxy outage looks
 * completely different: many *different* destinations failing to even connect through the proxy in
 * a short burst, because the proxy (or the path to it) is down, not because any particular app is
 * pinned. [TcpRelayManager] calls [recordFailure] once per proxy-eligible flow whose CONNECT still
 * fails after its own retry (see [TcpRelayManager.attemptConnect]) -- see
 * [VpnFilterService] for where the resulting signal turns into a Guardian-facing alert.
 *
 * Deliberately in-memory only, unlike [PinningFailureTracker]'s persisted-across-restarts state: a
 * proxy outage is a live, current-tunnel-generation condition -- a fresh tracker (and a clean
 * count) on every [VpnFilterService] reestablish() is exactly correct here, not a gap to fix, since
 * the whole point is reporting *current* proxy health, not something that should survive into a
 * brand new tunnel generation.
 *
 * This is a one-directional visibility signal only -- it never auto-remediates, never touches
 * [MitmExemptManager], and has no effect on the fail-closed RST behavior for the connections it
 * observes. It only decides *when to raise a distinct alert* about infrastructure health.
 */
class ProxyOutageTracker(
    // Overridable purely for deterministic unit testing of the window/pruning behavior below
    // without sleeping 30 real seconds -- defaults to real wall-clock time for every production
    // caller.
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    // dstIp -> most recent failure timestamp. A map (not a list) so repeated failures for the same
    // destination -- e.g. one genuinely-down site retried by the client many times -- collapse to a
    // single entry instead of inflating the distinct-destination count; that's the whole point of
    // keying on destination rather than counting raw failures.
    private val lastFailureByDestination = HashMap<String, Long>()

    // True once the current run of failures has already crossed the threshold and been reported --
    // reset back to false once the count drops back under threshold (see recordFailure), so a
    // *new* outage (after a real recovery) can raise a fresh signal rather than being silenced
    // forever after the first one.
    private var alreadyCrossedThreshold = false

    /**
     * Records one failed proxy CONNECT (both attempts already exhausted -- see
     * [TcpRelayManager.establish]) for [dstIp]. Returns true only on the call that *just* crosses
     * [OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD] distinct destinations within
     * [OUTAGE_WINDOW_MS] -- not on every subsequent call while still above threshold, and not
     * again until the count has dropped back under threshold and re-crossed. The caller
     * ([VpnFilterService]) still routes the resulting alert through [AlertReporter]'s own
     * debounce, so this is a belt-and-suspenders edge trigger, not the only thing preventing an
     * alert flood.
     */
    @Synchronized
    fun recordFailure(dstIp: String): Boolean {
        val now = nowProvider()
        lastFailureByDestination[dstIp] = now
        lastFailureByDestination.entries.removeAll { now - it.value > OUTAGE_WINDOW_MS }

        val aboveThreshold = lastFailureByDestination.size >= OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD
        if (!aboveThreshold) {
            alreadyCrossedThreshold = false
            return false
        }
        if (alreadyCrossedThreshold) return false
        alreadyCrossedThreshold = true
        return true
    }

    companion object {
        // Much shorter than PinningFailureTracker's 24h window: an outage is a live condition to
        // catch and alert on quickly, not a slow-accumulating per-app signal.
        const val OUTAGE_WINDOW_MS = 30_000L

        // A pinning failure is inherently single-app/single-destination-repeated (see
        // PinningFailureHeuristic); a proxy outage instead manifests as many *different*
        // destinations failing in a short burst. 5 distinct destinations in 30 seconds is already
        // an unusual concentration of connect failures for normal usage (where most flows succeed),
        // but low enough to catch a real outage quickly rather than waiting for dozens of failures.
        const val OUTAGE_DISTINCT_DESTINATIONS_THRESHOLD = 5
    }
}
