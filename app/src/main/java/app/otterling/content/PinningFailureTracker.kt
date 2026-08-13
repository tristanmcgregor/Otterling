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
    private val appContext = context.applicationContext
    private val exemptManager = MitmExemptManager(appContext)
    private val packageManager = appContext.packageManager
    private val recentFailures = mutableMapOf<Int, MutableList<Long>>()
    private val lock = Any()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

        // Any Play-Store-installable app can deliberately shape a few short HTTPS connections to
        // match this heuristic and get itself auto-exempted from content filtering -- there's no
        // stronger corroborating signal available at this layer (no visibility into the actual TLS
        // alert). A per-install cap bounds how many packages this path can silently exempt before
        // a Guardian has to look: a genuine broken-pinning case is rare enough that this ceiling
        // should essentially never bind in practice, while a self-triggered abuse pattern hits it
        // fast and then requires manual action instead of being able to repeat indefinitely.
        val autoExemptCount = prefs.getInt(KEY_AUTO_EXEMPT_COUNT, 0)
        if (autoExemptCount >= MAX_AUTO_EXEMPTIONS) {
            Log.w(
                TAG,
                "Auto-exemption cap ($MAX_AUTO_EXEMPTIONS) reached -- refusing to auto-exempt " +
                    "uid=$uid (${packages.joinToString()}); a Guardian must add it manually in " +
                    "Settings if it's a genuine pinning break",
            )
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
        if (addedAny) {
            prefs.edit().putInt(KEY_AUTO_EXEMPT_COUNT, autoExemptCount + 1).apply()
        }
        return addedAny
    }

    /** How many auto-exemptions have been used, out of [MAX_AUTO_EXEMPTIONS] -- surfaced in
     *  Settings so a Guardian can tell *why* a new pinned app stopped getting auto-exempted
     *  (previously this cap had no visibility at all: it just silently stopped working, logging
     *  only to logcat). */
    fun autoExemptCount(): Int = prefs.getInt(KEY_AUTO_EXEMPT_COUNT, 0)

    /** Frees up the cap again, e.g. once a Guardian has reviewed the apps it already auto-exempted
     *  (still visible/removable individually in Settings' exempt list either way). Doesn't touch
     *  [exemptManager]'s list itself -- this only resets how many *more* auto-exemptions can happen. */
    fun resetAutoExemptCount() {
        prefs.edit().putInt(KEY_AUTO_EXEMPT_COUNT, 0).apply()
    }

    companion object {
        const val MAX_AUTO_EXEMPTIONS = 10
        private const val TAG = "PinningFailureTracker"
        private const val WINDOW_MS = 120_000L
        private const val FAILURE_THRESHOLD = 3
        private const val PREFS_NAME = "pinning_failure_tracker_prefs"
        private const val KEY_AUTO_EXEMPT_COUNT = "auto_exempt_count"
    }
}
