package app.otterling.content

import android.content.Context

/**
 * Stores the set of app package names that should bypass [VpnFilterService] entirely -- routed
 * over the normal network instead of through our tunnel/relay. Some apps (Android Auto, banking
 * apps with certificate pinning, etc.) break when their traffic is captured by any VPN -- and,
 * specifically, break under *any* MITM proxy, not just this one, since certificate pinning
 * validates the exact leaf certificate/public key, which a MITM proxy's own on-the-fly-generated
 * certificate can never match. This lets the user exempt individual apps while everything else
 * stays filtered; [DEFAULT_BYPASS_PACKAGES] seeds the common ones (YouTube, AU banking apps) so
 * that works out of the box instead of the Guardian needing to know to add them.
 *
 * Applied via `VpnService.Builder.addDisallowedApplication()` when the tunnel is (re)established.
 */
class VpnBypassManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        seedDefaultsIfNeeded()
    }

    fun bypassPackages(): Set<String> = prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun add(packageName: String) {
        prefs.edit().putStringSet(KEY_PACKAGES, bypassPackages() + packageName).apply()
    }

    fun remove(packageName: String) {
        prefs.edit().putStringSet(KEY_PACKAGES, bypassPackages() - packageName).apply()
    }

    /**
     * One-time merge of [DEFAULT_BYPASS_PACKAGES] into whatever's already stored -- runs at most
     * once ever per install (tracked by [KEY_SEEDED_DEFAULTS], separate from the package set
     * itself), so a Guardian who later deliberately removes one of these isn't fought by having it
     * silently re-added on the next app start. Existing installs pick this up the first time this
     * class is constructed after updating, same as a fresh install.
     */
    private fun seedDefaultsIfNeeded() {
        if (prefs.getBoolean(KEY_SEEDED_DEFAULTS, false)) return
        prefs.edit()
            .putStringSet(KEY_PACKAGES, bypassPackages() + DEFAULT_BYPASS_PACKAGES)
            .putBoolean(KEY_SEEDED_DEFAULTS, true)
            .apply()
    }

    companion object {
        /**
         * Apps that certificate-pin and so break under any MITM proxy (not just ours) -- bypassing
         * them by default keeps YouTube and everyday AU banking working out of the box. Path-level
         * rules (e.g. YouTube Shorts) still apply via accessibility
         * ([app.otterling.focus.UrlPathBlockEnforcer]), which doesn't need MITM at all -- only
         * whole-app MITM interception is what these apps can't tolerate. The Guardian can still
         * remove any of these, or add more, in Settings; this is just the starting point.
         */
        val DEFAULT_BYPASS_PACKAGES = setOf(
            "com.google.android.youtube", // YouTube
            "com.commbank.netbank", // Commonwealth Bank (CommBank)
            "org.westpac.bank", // Westpac
            "au.com.up.money", // Up (neobank)
            "au.com.suncorp.rsa.suncorpsecured", // Suncorp secure banking app
        )

        private const val PREFS_NAME = "vpn_bypass_prefs"
        private const val KEY_PACKAGES = "bypass_packages"
        private const val KEY_SEEDED_DEFAULTS = "seeded_defaults_v1"
    }
}
