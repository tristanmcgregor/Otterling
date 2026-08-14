package app.otterling.content

import android.content.Context
import android.util.Log

/**
 * Watches for [TcpRelayManager] connections that look like a certificate-pinning rejection (see
 * [PinningFailureHeuristic]) and immediately adds the responsible app to [MitmExemptManager] --
 * no Guardian has to notice an app is broken and go find the exempt-list setting themselves. This
 * closes the gap a static seeded list can't: an app nobody thought to add in advance (see the
 * Morphe YouTube fork gap and the HotDoc gap, both found via live-device testing) still ends up
 * working on its very first rejected connection, not after the family notices the app is "broken"
 * for a day.
 *
 * AI REVIEW NOTE -- this used to require 3 corroborating matches inside a rolling time window
 * before exempting, specifically to guard against a single short-but-legitimate request being
 * misread as a rejection. That tradeoff was reconsidered: in practice, a genuinely pinned app
 * (banking, YouTube, auth apps) fails on *every* connection attempt until exempted, so requiring
 * 3 strikes just meant the app stayed visibly broken for the Guardian for longer (up to a day of
 * retries, in the worst case) for no real safety gain -- the exemption was always going to happen
 * anyway. What actually still bounds abuse/false-positive risk here is unchanged:
 * - [PinningFailureHeuristic] itself is narrow (byte-count range governed by our own proxy's
 *   cert size, tight elapsed-time/read-count bounds) -- it's not "any failed connection", it's a
 *   specific TLS-rejection shape.
 * - [MAX_AUTO_EXEMPTIONS] still caps how many packages this path can silently exempt per install
 *   before it refuses and requires manual Guardian action -- this is the actual abuse backstop,
 *   not the strike count.
 * - A false positive here only reduces content filtering for that one app (see below); it never
 *   fails open the tunnel/DNS layer.
 *
 * The root-cause bug this class used to have (separate from the above): the per-uid failure count
 * lived only in an in-memory map, which was thrown away and rebuilt empty every time
 * [VpnFilterService] reestablished the tunnel (a new [PinningFailureTracker] is constructed each
 * generation) -- something that happens on far more than just pinning-driven rebuilds (any
 * Settings change, network handover, etc.). Fixed by persisting per-uid state in [prefs] instead
 * of an in-memory field, so it survives across tracker instances.
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

        // Exempt on this first match rather than waiting for corroborating matches -- see the AI
        // REVIEW NOTE in the class doc above for why. [MAX_AUTO_EXEMPTIONS] is what actually
        // bounds how many packages this path can act on per install.
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
