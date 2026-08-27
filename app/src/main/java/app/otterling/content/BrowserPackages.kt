package app.otterling.content

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Resolves which installed packages can act as a web browser, by asking [PackageManager] which
 * apps handle an `http`/`https` VIEW intent.
 *
 * ## Why this exists
 *
 * [MitmExemptManager.NEVER_EXEMPT_PACKAGES] used to be four hardcoded Chrome package names, on the
 * reasoning that "a general browser can be pointed at literally any site, so exempting one would
 * exempt all web browsing done through it, defeating content filtering entirely." That reasoning
 * is exactly right and the list was far too small to deliver it: Firefox, Brave, Samsung Internet,
 * Edge, Opera, Vivaldi, DuckDuckGo, Kiwi, any WebView wrapper and any sideloaded browser were all
 * absent, so all of them remained eligible for exemption.
 *
 * That mattered because exemption is not only a Guardian action. [PinningFailureTracker]
 * auto-exempts an app after [PinningFailureTracker] sees two connections whose shape matches
 * [PinningFailureHeuristic] -- a fast, small, few-read TLS close, which any client can produce
 * deliberately by opening a connection, reading the substituted certificate and hanging up. So
 * installing a non-Chrome browser and making two crafted connections removed page-content review
 * for all browsing through it, leaving only DNS-level filtering that [VpnFilterService] itself
 * documents as "deliberately permissive about ambiguous-but-not-known-bad domains... since it
 * normally trusts this MITM hop." `VpnFilterService`'s own comments record the Chrome-only
 * fallback of 2026-08-18 being reverted for precisely this class of gap; the auto-exempt path
 * reopened it.
 *
 * ## Failure behaviour
 *
 * Fails toward MORE protection: if the query throws or returns nothing, [resolve] returns the
 * hardcoded floor rather than an empty set, so a `PackageManager` problem can never turn into
 * "no browser is protected." The floor is kept as a floor precisely so this can't regress to
 * worse-than-before.
 */
object BrowserPackages {

    /**
     * Queried, not hardcoded. `MATCH_ALL` so a browser that isn't currently the default still
     * resolves -- the default browser is irrelevant here, since the concern is any app capable of
     * arbitrary web browsing, not the one the user happens to have chosen.
     */
    fun resolve(context: Context, floor: Set<String>): Set<String> {
        val found = mutableSetOf<String>()
        val packageManager = context.applicationContext.packageManager

        // Both schemes: some browsers register only one, and a sideloaded APK may register either.
        for (scheme in listOf("http", "https")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.fromParts(scheme, "", null)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            try {
                val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                }
                for (info in activities) {
                    info.activityInfo?.packageName?.let { found.add(it) }
                }
            } catch (error: Exception) {
                // Never fatal: fall through to the floor below rather than let a PackageManager
                // problem silently reduce coverage.
                Log.w(TAG, "browser query failed for scheme=$scheme", error)
            }
        }

        if (found.isEmpty()) {
            Log.w(TAG, "resolved no browsers -- falling back to the hardcoded floor (${floor.size} packages)")
            return floor
        }

        // Union, not replacement. If a Chrome channel is somehow not resolvable on this device, it
        // must still never be exemptible.
        val result = found + floor
        Log.i(TAG, "browsers that may never be MITM-exempt (${result.size}): ${result.sorted().joinToString()}")
        return result
    }

    private const val TAG = "BrowserPackages"
}
