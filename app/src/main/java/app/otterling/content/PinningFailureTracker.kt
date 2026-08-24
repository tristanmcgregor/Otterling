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
 * false positive or a single crafted connection, to get silently exempted from content filtering.
 * Settled on 2: still a corroboration requirement (a lone matching connection is never enough),
 * but half the wait of the original 3 for genuinely pinned apps, which fail the same way on
 * essentially every connection attempt until exempted.
 *
 * There used to be a per-install cap ([FAILURE_THRESHOLD]'s neighbor, MAX_AUTO_EXEMPTIONS) on how
 * many packages this path could auto-exempt in total, meant to bound a hypothetical abuse pattern
 * (deliberately shaping connections to get many packages auto-exempted). Removed at the Guardian's
 * explicit request: in practice it just meant a real pinned app stopped getting fixed once the
 * count ran out, with no clear benefit to show for it -- [FAILURE_THRESHOLD]'s corroboration
 * requirement is still the actual defense against a single crafted connection.
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

        // No cap on how many packages this path can auto-exempt in total -- see the class doc's
        // note on why that cap was removed. [FAILURE_THRESHOLD]'s corroboration requirement (not
        // a totals ceiling) is what stops a single crafted connection from getting an app
        // auto-exempted.
        val autoExemptCount = prefs.getInt(KEY_AUTO_EXEMPT_COUNT, 0)
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

    /** How many auto-exemptions have happened in total -- surfaced in Settings purely for
     *  Guardian visibility (no cap tied to it anymore, see the class doc's note). */
    fun autoExemptCount(): Int = prefs.getInt(KEY_AUTO_EXEMPT_COUNT, 0)

    companion object {
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
