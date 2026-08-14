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
 * [DEFAULT_EXEMPT_PACKAGES] (plus the later [DEFAULT_EXEMPT_PACKAGES_V2]/[DEFAULT_EXEMPT_PACKAGES_V3])
 * seeds the common ones (YouTube, AU banking apps, HotDoc, Google Authenticator) so this works out
 * of the box instead of the Guardian needing to know to add them.
 *
 * Applied via [AppUidResolver]-based flow attribution when the tunnel is (re)established --
 * see [VpnFilterService.runPacketLoop].
 */
class MitmExemptManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        seedDefaultsIfNeeded()
        seedV2DefaultsIfNeeded()
        seedV3DefaultsIfNeeded()
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

    /**
     * Same one-time-merge pattern as [seedDefaultsIfNeeded], but on its own flag ([KEY_SEEDED_V2])
     * so [DEFAULT_EXEMPT_PACKAGES_V2] (apps identified as needing this after the original list
     * shipped) still gets merged into an *already-provisioned* device the first time it runs this
     * updated build -- not just fresh installs -- without re-adding anything from the v1 list a
     * Guardian may have since deliberately removed.
     */
    private fun seedV2DefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED_V2, false)) return
        prefs.edit()
            .putStringSet(KEY_PACKAGES, exemptPackages() + DEFAULT_EXEMPT_PACKAGES_V2)
            .putBoolean(KEY_SEEDED_V2, true)
            .apply()
    }

    /** Same one-time-merge pattern as [seedDefaultsIfNeeded]/[seedV2DefaultsIfNeeded], for
     *  [DEFAULT_EXEMPT_PACKAGES_V3]. */
    private fun seedV3DefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED_V3, false)) return
        prefs.edit()
            .putStringSet(KEY_PACKAGES, exemptPackages() + DEFAULT_EXEMPT_PACKAGES_V3)
            .putBoolean(KEY_SEEDED_V3, true)
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

        /** Added after the original list shipped -- see [seedV2DefaultsIfNeeded]. */
        val DEFAULT_EXEMPT_PACKAGES_V2 = setOf(
            "au.com.hotdoc.android.hotdoc", // HotDoc (medical appointment booking)
        )

        /** Added after the v2 list shipped -- see [seedV3DefaultsIfNeeded]. Google Authenticator's
         *  own cert-pinned Google-account backup/sync check only runs occasionally (not promptly
         *  retried like YouTube's), so [PinningFailureTracker]'s auto-exempt path would otherwise
         *  need up to a day to gather 3 corroborating failures -- seeded here for immediate relief
         *  in the meantime, same reasoning as HotDoc above. */
        val DEFAULT_EXEMPT_PACKAGES_V3 = setOf(
            "com.google.android.apps.authenticator2", // Google Authenticator
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
        private const val KEY_SEEDED_V2 = "seeded_defaults_v2"
        private const val KEY_SEEDED_V3 = "seeded_defaults_v3"
    }
}
