package app.otterling.content

import android.content.Context
import android.util.Log

/**
 * Watches for [TcpRelayManager] connections that look like a certificate-pinning rejection (see
 * [PinningFailureHeuristic]) and, once the *same app* has done this [FAILURE_THRESHOLD] times
 * within [WINDOW_MS], adds it to [MitmExemptManager] automatically -- no Guardian has to notice an
 * app is broken and go find the exempt-list setting themselves. This is the whole point: the
 * static seeded list only covers apps someone thought to add in advance (see the Morphe YouTube
 * fork gap found via live-device testing); this closes that gap for every app, seeded or not.
 *
 * Requiring several occurrences in a short window, rather than acting on the first one, filters
 * out the case where a single ordinary short-but-successful connection coincidentally matches the
 * heuristic -- a genuinely pinned app fails on essentially every connection attempt and typically
 * retries aggressively, so this bar is fast to clear for a real rejection and unlikely to be hit by
 * chance for an app that doesn't actually need exempting.
 *
 * A false negative here just leaves an app broken until the threshold is met (seconds, in
 * practice) or a Guardian adds it manually, same as today. A false positive silently reduces
 * content filtering for that one app -- undesirable, but not a fail-open of the tunnel/DNS layer,
 * which is unaffected either way (see [TcpRelayManager]/[MitmExemptManager]'s own docs).
 */
class PinningFailureTracker(context: Context) {
    private val exemptManager = MitmExemptManager(context.applicationContext)
    private val packageManager = context.applicationContext.packageManager
    private val recentFailures = mutableMapOf<Int, MutableList<Long>>()
    private val lock = Any()

    /** Returns true if this call caused a *new* auto-exemption (caller should reestablish the
     *  tunnel so it takes effect on the next connection attempt). */
    fun recordSuspectedFailure(uid: Int): Boolean {
        val now = System.currentTimeMillis()
        val thresholdReached = synchronized(lock) {
            val timestamps = recentFailures.getOrPut(uid) { mutableListOf() }
            timestamps.add(now)
            timestamps.removeAll { now - it > WINDOW_MS }
            (timestamps.size >= FAILURE_THRESHOLD).also {
                if (it) recentFailures.remove(uid) // reset so a repeat pass doesn't re-trigger every failure
            }
        }
        if (!thresholdReached) return false

        val packages = try {
            packageManager.getPackagesForUid(uid)
        } catch (error: Exception) {
            Log.w(TAG, "getPackagesForUid($uid) failed", error)
            null
        }
        if (packages.isNullOrEmpty()) {
            Log.w(TAG, "Repeated suspected pinning failures for uid=$uid but couldn't resolve a package name")
            return false
        }

        val alreadyExempt = exemptManager.exemptPackages()
        var addedAny = false
        for (pkg in packages) {
            if (pkg !in alreadyExempt) {
                exemptManager.add(pkg)
                addedAny = true
                Log.i(TAG, "Auto-exempted $pkg (uid=$uid) from MITM after $FAILURE_THRESHOLD suspected pinning rejections")
            }
        }
        return addedAny
    }

    private companion object {
        const val TAG = "PinningFailureTracker"
        const val WINDOW_MS = 120_000L
        const val FAILURE_THRESHOLD = 3
    }
}
