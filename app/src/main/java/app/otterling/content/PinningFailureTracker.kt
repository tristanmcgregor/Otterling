package app.otterling.content

import android.content.Context
import android.util.Log

/**
 * Watches for [TcpRelayManager] connections that look like a certificate-pinning rejection (see
 * [PinningFailureHeuristic]) and adds the app to [MitmExemptManager] automatically the moment one
 * is seen -- no Guardian has to notice an app is broken and go find the exempt-list setting
 * themselves, and no waiting on repeated failures either: a single pinned app that shows a user
 * "can't connect to server" once has already failed for real (see [PinningFailureHeuristic] for
 * why that one connection's shape is trusted). This is the whole point: the static seeded list
 * only covers apps someone thought to add in advance (see the Morphe YouTube fork gap found via
 * live-device testing, and the HotDoc gap found the same way); this closes that gap for every app,
 * seeded or not, on first occurrence.
 *
 * Acting on the very first match (previously this required 3 occurrences inside a rolling 2-minute
 * window) also sidesteps a real bug that window had: the count lived only in this class's
 * in-memory map, which is thrown away and rebuilt fresh every time [VpnFilterService] reestablishes
 * the tunnel (a new [PinningFailureTracker] is constructed each generation) -- something that
 * happens on far more than just pinning-driven rebuilds (any Settings change, network handover,
 * etc.). In practice a real pinned app's failures were often spread across several tunnel
 * generations and never accumulated to 3 within any single one, so the auto-exempt path silently
 * never fired for it.
 *
 * A false positive here silently reduces content filtering for that one app -- undesirable, but
 * not a fail-open of the tunnel/DNS layer, which is unaffected either way (see
 * [TcpRelayManager]/[MitmExemptManager]'s own docs); see [PinningFailureHeuristic]'s doc for why a
 * single match is trusted rather than requiring corroboration.
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

        val alreadyExempt = exemptManager.exemptPackages()
        var addedAny = false
        for (pkg in packages) {
            if (pkg !in alreadyExempt) {
                exemptManager.add(pkg)
                addedAny = true
                Log.i(TAG, "Auto-exempted $pkg (uid=$uid) from MITM after a suspected pinning rejection")
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
        private const val PREFS_NAME = "pinning_failure_tracker_prefs"
        private const val KEY_AUTO_EXEMPT_COUNT = "auto_exempt_count"
    }
}
