package au.com.tbmcgregor.bwparker.familyguard.content

import android.content.Context

/**
 * Stores the set of app package names that should bypass [VpnFilterService] entirely -- routed
 * over the normal network instead of through our tunnel/relay. Some apps (Android Auto, banking
 * apps with certificate pinning, etc.) break when their traffic is captured by any VPN; this lets
 * the user exempt them individually while everything else stays filtered.
 *
 * Applied via `VpnService.Builder.addDisallowedApplication()` when the tunnel is (re)established.
 */
class VpnBypassManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun bypassPackages(): Set<String> = prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun add(packageName: String) {
        prefs.edit().putStringSet(KEY_PACKAGES, bypassPackages() + packageName).apply()
    }

    fun remove(packageName: String) {
        prefs.edit().putStringSet(KEY_PACKAGES, bypassPackages() - packageName).apply()
    }

    private companion object {
        const val PREFS_NAME = "vpn_bypass_prefs"
        const val KEY_PACKAGES = "bypass_packages"
    }
}
