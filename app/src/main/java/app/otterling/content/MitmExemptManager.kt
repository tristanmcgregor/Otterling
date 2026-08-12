package app.otterling.content

import android.content.Context

/**
 * Stores the set of app package names exempt from the mitmproxy MITM hop -- these apps stay
 * *inside* the VPN tunnel (their DNS still goes through [DomainBlocklistManager]/cloud AdGuard,
 * and QUIC/443-UDP is still dropped for them, same as everything else) but their TCP 80/443 flows
 * connect directly to the real destination instead of being CONNECT-proxied through mitmproxy --
 * see [TcpRelayManager.establish] and [MitmExemptionPolicy]. This exists because certificate
 * pinning (YouTube, banking apps) validates the exact leaf certificate/public key, which a MITM
 * proxy's own on-the-fly-generated certificate can never match -- exempting just the proxy hop
 * (not the whole tunnel) keeps these apps working *and* still DNS-filtered, rather than fully
 * unfiltered the way a `VpnService`-level bypass would leave them.
 *
 * [DEFAULT_EXEMPT_PACKAGES] seeds the common ones (YouTube, AU banking apps) so this works out of
 * the box instead of the Guardian needing to know to add them.
 *
 * Applied via [AppUidResolver]-based flow attribution when the tunnel is (re)established --
 * see [VpnFilterService.runPacketLoop].
 */
class MitmExemptManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        seedDefaultsIfNeeded()
    }

    fun exemptPackages(): Set<String> = prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    /**
     * No-ops for [NEVER_EXEMPT_PACKAGES] -- unlike YouTube/banking, which only ever talk to their
     * own pinned endpoints, a general browser can be pointed at literally any site, so exempting
     * one from HTTPS interception would exempt all web browsing done through it, defeating content
     * filtering entirely. Enforced here (not just hidden from the app picker in
     * [app.otterling.ui.VpnFilterSection]) so nothing that calls this directly can add it either.
     */
    fun add(packageName: String) {
        if (packageName in NEVER_EXEMPT_PACKAGES) return
        prefs.edit().putStringSet(KEY_PACKAGES, exemptPackages() + packageName).apply()
    }

    fun remove(packageName: String) {
        prefs.edit().putStringSet(KEY_PACKAGES, exemptPackages() - packageName).apply()
    }

    /**
     * One-time merge of [DEFAULT_EXEMPT_PACKAGES] into whatever's already stored -- runs at most
     * once ever per install (tracked by [KEY_SEEDED_DEFAULTS], separate from the package set
     * itself), so a Guardian who later deliberately removes one of these isn't fought by having it
     * silently re-added on the next app start. Existing installs pick this up the first time this
     * class is constructed after updating, same as a fresh install.
     */
    private fun seedDefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED_DEFAULTS, false)) return
        prefs.edit()
            .putStringSet(KEY_PACKAGES, exemptPackages() + DEFAULT_EXEMPT_PACKAGES)
            .putBoolean(KEY_SEEDED_DEFAULTS, true)
            .apply()
    }

    companion object {
        /**
         * Apps that certificate-pin and so break under any MITM proxy (not just ours) -- exempting
         * them by default keeps YouTube and everyday AU banking working, and DNS-filtered, out of
         * the box. Path-level rules (e.g. YouTube Shorts) still apply via accessibility
         * ([app.otterling.focus.UrlPathBlockEnforcer]), which doesn't need MITM at all -- only
         * whole-flow MITM interception is what these apps can't tolerate. The Guardian can still
         * remove any of these, or add more, in Settings; this is just the starting point.
         */
        val DEFAULT_EXEMPT_PACKAGES = setOf(
            "com.google.android.youtube", // YouTube
            "app.morphe.android.youtube", // Morphe (YouTube client fork; talks to the same
            // pinned Google/YouTube endpoints as the official app)
            "com.commbank.netbank", // Commonwealth Bank (CommBank)
            "org.westpac.bank", // Westpac
            "au.com.up.money", // Up (neobank)
            "au.com.suncorp.rsa.suncorpsecured", // Suncorp secure banking app
        )

        /** Chrome (all channels) can never be added to [exemptPackages] -- see [add]. */
        val NEVER_EXEMPT_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
        )

        private const val PREFS_NAME = "vpn_bypass_prefs"
        private const val KEY_PACKAGES = "bypass_packages"
        private const val KEY_SEEDED_DEFAULTS = "seeded_defaults_v1"
    }
}
