package app.otterling.content

import android.content.Context

/**
 * ## Dashboard-driven bypass list (Phase 1 of `dashboard/SERVER_DRIVEN_CONFIG_PLAN.md`)
 *
 * [exemptPackages] is the union of the local set below (seeded defaults +
 * [PinningFailureTracker]'s auto-exemptions + anything still added via this device's own
 * Settings UI) with `vpnBypassApps` from [DashboardConfigStore]'s cached
 * `device_settings.json` record -- additive for now, not a replacement, since the seeded
 * defaults and auto-exempt path are technical necessities (cert-pinning compat), not guardian
 * config the dashboard should own outright, and this device's own Settings UI hasn't been
 * removed yet (that's Phase 8, once this phase is proven stable). [NEVER_EXEMPT_PACKAGES] is
 * stripped from the merged result too, not just at [add]-time, so a stale cached dashboard
 * snapshot from before the server-side veto existed can't reintroduce it.
 *
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
 * [DEFAULT_EXEMPT_PACKAGES] (plus the later [DEFAULT_EXEMPT_PACKAGES_V2]/[DEFAULT_EXEMPT_PACKAGES_V3]/
 * [DEFAULT_EXEMPT_PACKAGES_V4]) seeds the common ones (YouTube, AU banking apps, HotDoc, Google
 * Authenticator, Google Play Services, WhatsApp) so this works out of the box instead of the
 * Guardian needing to know to add them. Not every entry is here because of certificate pinning --
 * see [DEFAULT_EXEMPT_PACKAGES_V4]'s doc for WhatsApp's different (performance, not breakage) reason.
 *
 * Applied via [AppUidResolver]-based flow attribution when the tunnel is (re)established --
 * see [VpnFilterService.runPacketLoop].
 */
class MitmExemptManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        seedDefaultsIfNeeded()
        seedV2DefaultsIfNeeded()
        seedV3DefaultsIfNeeded()
        seedV4DefaultsIfNeeded()
    }

    fun exemptPackages(): Set<String> = (localPackages() + dashboardExemptPackages()) - NEVER_EXEMPT_PACKAGES

    /** The local-only set (seeded defaults, [add]/[remove], [PinningFailureTracker] auto-exempts)
     *  -- deliberately NOT merged with [dashboardExemptPackages], so the seed*IfNeeded methods
     *  below (which read-modify-write this) never bake a dashboard-sourced entry into local
     *  storage. Baking one in would survive a guardian later removing it from the dashboard,
     *  since it'd then look identical to a locally-added entry. */
    private fun localPackages(): Set<String> = prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    /** `vpnBypassApps` entries from [DashboardConfigStore]'s cache -- each item's `name` field is
     *  the exact Android package name (the dashboard's "Bypass apps" field, not a display name;
     *  see that field's placeholder text in the dashboard UI). Empty if nothing's been fetched
     *  yet, same "fail toward not-yet-configured rather than throwing" stance as
     *  [DashboardConfigStore.refresh] itself. Public (not just folded into [exemptPackages]) so
     *  [app.otterling.ui.VpnFilterSection] can tell dashboard-sourced entries apart from local
     *  ones -- [remove] is a no-op against these (it only ever touches local storage), so the
     *  UI needs to know not to offer a Remove button that would silently fail to do anything. */
    fun dashboardExemptPackages(): Set<String> {
        val apps = DashboardConfigStore(appContext).snapshot()?.optJSONArray("vpnBypassApps") ?: return emptySet()
        return buildSet {
            for (i in 0 until apps.length()) {
                val name = apps.optJSONObject(i)?.optString("name")?.trim()
                if (!name.isNullOrEmpty()) add(name)
            }
        }
    }

    /**
     * No-ops for [NEVER_EXEMPT_PACKAGES] -- unlike YouTube/banking, which only ever talk to their
     * own pinned endpoints, a general browser can be pointed at literally any site, so exempting
     * one from HTTPS interception would exempt all web browsing done through it, defeating content
     * filtering entirely. Enforced here (not just hidden from the app picker in
     * [app.otterling.ui.VpnFilterSection]) so nothing that calls this directly can add it either.
     */
    fun add(packageName: String) {
        if (packageName in NEVER_EXEMPT_PACKAGES) return
        prefs.edit().putStringSet(KEY_PACKAGES, localPackages() + packageName).apply()
    }

    fun remove(packageName: String) {
        prefs.edit().putStringSet(KEY_PACKAGES, localPackages() - packageName).apply()
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
            .putStringSet(KEY_PACKAGES, localPackages() + DEFAULT_EXEMPT_PACKAGES)
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
            .putStringSet(KEY_PACKAGES, localPackages() + DEFAULT_EXEMPT_PACKAGES_V2)
            .putBoolean(KEY_SEEDED_V2, true)
            .apply()
    }

    /** Same one-time-merge pattern as [seedDefaultsIfNeeded]/[seedV2DefaultsIfNeeded], for
     *  [DEFAULT_EXEMPT_PACKAGES_V3]. */
    private fun seedV3DefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED_V3, false)) return
        prefs.edit()
            .putStringSet(KEY_PACKAGES, localPackages() + DEFAULT_EXEMPT_PACKAGES_V3)
            .putBoolean(KEY_SEEDED_V3, true)
            .apply()
    }

    /** Same one-time-merge pattern as the earlier seed* methods, for [DEFAULT_EXEMPT_PACKAGES_V4]. */
    private fun seedV4DefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED_V4, false)) return
        prefs.edit()
            .putStringSet(KEY_PACKAGES, localPackages() + DEFAULT_EXEMPT_PACKAGES_V4)
            .putBoolean(KEY_SEEDED_V4, true)
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

        /**
         * Added after the v3 list shipped -- see [seedV4DefaultsIfNeeded]. Two different
         * problems, same fix:
         *
         * - Google Play Services/Services Framework own the device's "add a Google account" flow,
         *   which hits cert-pinned endpoints (`android.clients.google.com` and others) -- same
         *   pinning-breakage reasoning as every other entry above, just system-level packages
         *   instead of a regular app. Without this, adding a new Google account on the device fails
         *   outright.
         * - WhatsApp is a different problem entirely, not pinning: its media (photos/videos sent or
         *   received) is end-to-end encrypted, so the bytes mitmproxy would inspect are ciphertext
         *   it can never classify -- `mitm_nsfw_addon.py` already bails out on any non-`text/html`
         *   response, so MITM-ing this traffic was providing zero filtering benefit while still
         *   paying the full per-connection CONNECT/cert-generation cost on every single image,
         *   which compounds badly since WhatsApp opens many short-lived connections rather than one
         *   persistent stream. Exempting it removes pure overhead, not filtering coverage.
         */
        val DEFAULT_EXEMPT_PACKAGES_V4 = setOf(
            "com.google.android.gms", // Google Play Services (owns account setup)
            "com.google.android.gsf", // Google Services Framework
            "com.whatsapp", // WhatsApp
            "com.whatsapp.w4b", // WhatsApp Business
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
        private const val KEY_SEEDED_V4 = "seeded_defaults_v4"
    }
}
