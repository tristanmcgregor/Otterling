package app.otterling.restrictions

import android.content.Context

/**
 * Persists the parent's chosen state for each restriction, separate from the live
 * [DevicePolicyManager][android.app.admin.DevicePolicyManager] state. Drift detection compares
 * the live state against this -- not a hardcoded "always on" default -- so a restriction the
 * parent intentionally turned off in Settings (e.g. to use ADB for maintenance) doesn't get
 * silently forced back on by the periodic drift checker. Only a live state that disagrees with
 * what was last chosen here counts as tampering.
 */
class RestrictionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDesired(restriction: Restriction): Boolean = prefs.getBoolean(restriction.name, true)

    fun setDesired(restriction: Restriction, desired: Boolean) {
        prefs.edit().putBoolean(restriction.name, desired).apply()
    }

    fun isUninstallBlockDesired(): Boolean = prefs.getBoolean(KEY_UNINSTALL_BLOCKED, true)

    fun setUninstallBlockDesired(desired: Boolean) {
        prefs.edit().putBoolean(KEY_UNINSTALL_BLOCKED, desired).apply()
    }

    private companion object {
        const val PREFS_NAME = "restriction_preferences"
        const val KEY_UNINSTALL_BLOCKED = "uninstall_blocked"
    }
}
