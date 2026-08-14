package app.otterling.content

import android.content.Context
import android.util.Log

/**
 * Watches for [TcpRelayManager] connections that look like a certificate-pinning rejection (see
 * [PinningFailureHeuristic]) and, once the same app has shown [FAILURE_THRESHOLD] of them inside
 * [WINDOW_MS], adds it to [MitmExemptManager] automatically -- no Guardian has to notice an app is
 * broken and go find the exempt-list setting themselves. This closes the gap a static seeded list
 * can't: an app nobody thought to add in advance (see the Morphe YouTube fork gap and the HotDoc
 * gap, both found via live-device testing) still ends up working, without lowering the bar enough
 * that a single short-but-legitimate request could trip it (see [PinningFailureHeuristic]'s doc
 * for why one match alone isn't trusted).
 *
 * AI REVIEW NOTE -- [FAILURE_THRESHOLD] was previously 3, then briefly dropped to 1 (exempt on
 * the very first match); AI review correctly rejected that as making it materially easier, via a
 * false positive or a single crafted connection, to get silently exempted from content filtering,
 * since [MAX_AUTO_EXEMPTIONS] alone doesn't stop one weak signal per package from working up to
 * that cap. Settled on 2: still a corroboration requirement (a lone matching connection is never
 * enough), but half the wait of the original 3 for genuinely pinned apps, which fail the same way
 * on essentially every connection attempt until exempted.
 *
 * The root-cause bug this class used to have: the per-uid failure count lived only in an
 * in-memory map, which was thrown away and rebuilt empty every time [VpnFilterService]
 * reestablished the tunnel (a new [PinningFailureTracker] is constructed each generation) --
 * something that happens on far more than just pinning-driven rebuilds (any Settings change,
 * network handover, etc.). In practice a real pinned app's failures were often spread across
 * several tunnel generations and never accumulated to [FAILURE_THRESHOLD] within any single one,
 * so the auto-exempt path silently never fired for it. Fixed by persisting each uid's recent
 * failure timestamps in [prefs] instead of an in-memory field, so they survive across tracker
 * instances and only expire via [WINDOW_MS] itself.
 *
 * A false positive here silently reduces content filtering for that one app -- undesirable, but
 * not a fail-open of the tunnel/DNS layer, which is unaffected either way (see
 * [TcpRelayManager]/[MitmExemptManager]'s own docs).
 */
class PinningFailureTracker(context: Context) {
    private val appContext = context.applicationContext
    private val exemptManager = MitmExemptManager(appContext)
    private val packageManager = appContext.packageManager
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns true if this call caused a *new* auto-exemption (caller should reestablish the
     *  tunnel so it takes effect on the next connection attempt). */
    fun recordSuspectedFailure(uid: Int): Boolean {
        val packages = try {
            packageManager.getPackagesForUid(uid)
        } catch (error: Exception) {
            Log.w(TAG, "getPackagesForUid($uid) failed", error)
            null
        }
        if (packages.isNullOrEmpty()) {
            Log.w(TAG, "Suspected pinning failure for uid=$uid but couldn't resolve a package name")
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

        val recentFailures = recordAndPruneFailureTimes(uid)
        if (recentFailures.size < FAILURE_THRESHOLD) {
            Log.d(
                TAG,
                "Suspected pinning failure ${recentFailures.size}/$FAILURE_THRESHOLD within " +
                    "${WINDOW_MS}ms for uid=$uid (${packages.joinToString()}); not exempting yet",
            )
            return false
        }
        prefs.edit().remove(failureTimesKey(uid)).apply()

        val alreadyExempt = exemptManager.exemptPackages()
        var addedAny = false
        for (pkg in packages) {
            if (pkg !in alreadyExempt) {
                exemptManager.add(pkg)
                addedAny = true
                Log.i(TAG, "Auto-exempted $pkg (uid=$uid) from MITM after ${recentFailures.size} suspected pinning rejections")
            }
        }
        if (addedAny) {
            prefs.edit().putInt(KEY_AUTO_EXEMPT_COUNT, autoExemptCount + 1).apply()
        }
        return addedAny
    }

    /** Appends `now` to uid's persisted failure-timestamp list, drops anything older than
     *  [WINDOW_MS], persists the pruned list back, and returns it. */
    private fun recordAndPruneFailureTimes(uid: Int): List<Long> {
        val now = System.currentTimeMillis()
        val key = failureTimesKey(uid)
        val existing = prefs.getString(key, "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.toLongOrNull() }
        val pruned = (existing + now).filter { now - it <= WINDOW_MS }
        prefs.edit().putString(key, pruned.joinToString(",")).apply()
        return pruned
    }

    private fun failureTimesKey(uid: Int) = "$KEY_FAILURE_TIMES_PREFIX$uid"

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
        private const val PREFS_NAME = "pinning_failure_tracker_prefs"
        private const val KEY_AUTO_EXEMPT_COUNT = "auto_exempt_count"
        private const val KEY_FAILURE_TIMES_PREFIX = "failure_times_uid_"

        // A full day comfortably covers apps that only attempt their cert-pinned check
        // occasionally (once per app open, sometimes less -- see Google Authenticator, which
        // needed this widened from the original 2 minutes), while still requiring corroborating
        // matches, not just one.
        private const val WINDOW_MS = 86_400_000L

        // See the AI REVIEW NOTE in the class doc: was 3, briefly dropped to 1 (rejected by
        // review), settled on 2 -- still requires corroboration, half the wait of the original.
        private const val FAILURE_THRESHOLD = 2
    }
}
