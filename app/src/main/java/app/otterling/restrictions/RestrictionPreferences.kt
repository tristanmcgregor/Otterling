package app.otterling.restrictions

import android.content.Context
import app.otterling.content.DashboardConfigStore

/**
 * Persists the parent's chosen state for each restriction, separate from the live
 * [DevicePolicyManager][android.app.admin.DevicePolicyManager] state. Drift detection compares
 * the live state against this -- not a hardcoded "always on" default -- so a restriction the
 * parent intentionally turned off in Settings (e.g. to use ADB for maintenance) doesn't get
 * silently forced back on by the periodic drift checker. Only a live state that disagrees with
 * what was last chosen here counts as tampering.
 *
 * ## Dashboard-driven desired state (Phase 3 of `dashboard/SERVER_DRIVEN_CONFIG_PLAN.md`)
 *
 * [isDesired]/[isUninstallBlockDesired] prefer [DashboardConfigStore]'s cached `protections`
 * value over the local one below, when present -- decision #1 in that plan settled on "server
 * always wins" for restriction enforcement precedence (unlike Phase 1/2's additive-merge lists,
 * a boolean can't be unioned; one side has to win, and it's the dashboard's). This is *only* the
 * value [detectDriftAndReapply][DeviceRestrictionsManager.detectDriftAndReapply] reapplies
 * toward -- [DeviceRestrictionsManager.setEnabled] still applies a local Settings-screen toggle
 * immediately and still fires `RESTRICTION_DISABLED_BY_USER`, it just won't survive the next
 * 5-minute drift check if the dashboard disagrees (which then fires its own `RESTRICTION_DRIFT`
 * alert restoring it) -- both existing alerts stay intact per the plan's cross-cutting reminder
 * about not losing that reporting. Falls back to the local value when the dashboard hasn't been
 * fetched yet or has no opinion on this key (decision #3: fail toward the last-known value, not
 * toward "unprotected").
 */
class RestrictionPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDesired(restriction: Restriction): Boolean =
        dashboardProtection(restriction.dashboardKey) ?: prefs.getBoolean(restriction.name, true)

    fun setDesired(restriction: Restriction, desired: Boolean) {
        prefs.edit().putBoolean(restriction.name, desired).apply()
    }

    /** True if the dashboard currently has an opinion on [restriction] -- i.e. [isDesired] is
     *  reading the dashboard's value, not the local one, so a Settings-screen toggle for it won't
     *  stick past the next drift check. Lets the on-device UI say so instead of silently reverting
     *  with no explanation. */
    fun isDashboardManaged(restriction: Restriction): Boolean = dashboardProtection(restriction.dashboardKey) != null

    fun isUninstallBlockDesired(): Boolean =
        dashboardProtection(KEY_UNINSTALL_BLOCK_DASHBOARD) ?: prefs.getBoolean(KEY_UNINSTALL_BLOCKED, true)

    fun setUninstallBlockDesired(desired: Boolean) {
        prefs.edit().putBoolean(KEY_UNINSTALL_BLOCKED, desired).apply()
    }

    /** Same as [isDashboardManaged] but for [isUninstallBlockDesired]. */
    fun isUninstallBlockDashboardManaged(): Boolean = dashboardProtection(KEY_UNINSTALL_BLOCK_DASHBOARD) != null

    private fun dashboardProtection(key: String?): Boolean? {
        if (key == null) return null
        val protections = DashboardConfigStore(appContext).snapshot()?.optJSONObject("protections") ?: return null
        return if (protections.has(key)) protections.optBoolean(key, true) else null
    }

    private companion object {
        const val PREFS_NAME = "restriction_preferences"
        const val KEY_UNINSTALL_BLOCKED = "uninstall_blocked"
        const val KEY_UNINSTALL_BLOCK_DASHBOARD = "uninstallBlock"
    }
}
