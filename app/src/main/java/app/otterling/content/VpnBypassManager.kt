package app.otterling.content

import android.content.Context

/**
 * Stores the set of app package names that should bypass [VpnFilterService] entirely -- routed
 * over the normal network instead of through our tunnel/relay.
 *
 * Prefer keeping MITM off ([CloudFilterSettings.isProxyEnabled]) so certificate-pinned apps
 * (YouTube, banking) work *inside* the VPN with DNS filtering. This list is for the rare cases
 * where an app still breaks under any VPN (e.g. Android Auto) or a Guardian has turned MITM on
 * and needs an escape for a specific package. Defaults are empty -- compatibility is not achieved
 * by exempting apps from the filter.
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
