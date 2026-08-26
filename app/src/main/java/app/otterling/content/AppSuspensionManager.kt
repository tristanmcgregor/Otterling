package app.otterling.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.otterling.admin.DeviceAdminReceiverImpl
import app.otterling.data.AppDatabase
import app.otterling.data.BlockedApp
import app.otterling.restrictions.PackageBlockEnforcer
import app.otterling.restrictions.PackageDisableStore
import app.otterling.tamper.TamperEventLogger

/**
 * Blocks apps via package suspend (or disable-user fallback for device-admin apps) and persists
 * the chosen list so it can be re-applied after a reboot. Requires Device Owner.
 *
 * ## Dashboard-driven blocks (Phase 7 of `dashboard/SERVER_DRIVEN_CONFIG_PLAN.md`)
 *
 * [blockedApps] additively merges the dashboard's `blockedApps` list (keyed by `appId`, the
 * Android package name -- there's no separate synthetic id, since [BlockedApp.packageName] is
 * already the Room primary key) into whatever's in Room, dashboard winning on `blocked` for a
 * package present on both sides (there's no on-device production UI for this list today --
 * [app.otterling.monitoring.DebugUnsuspendReceiver] is the only other writer, debug-only -- so
 * this conflict case is mostly theoretical, but "server wins" stays consistent with every other
 * phase). [setBlocked]/[remove] only ever touch Room, same as [app.otterling.focus.AppTimeBudgetManager]'s
 * Phase 4 pattern -- no risk of a merged read leaking into a local write.
 *
 * [reapplyAll] additionally clears a dashboard-managed package's [PackageDisableStore] exemption
 * before reasserting the block, so tapping "Undisable" in the on-device "Disabled apps" screen
 * doesn't permanently defeat a guardian's dashboard block the way it would a local/debug one --
 * confirmed as the intended behavior when this phase was built, matching decision #1's "server
 * always wins" and how dashboard-driven protections (Phase 3) already survive a local override.
 */
class AppSuspensionManager(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).blockedAppDao()
    private val pm = context.packageManager
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    suspend fun blockedApps(): List<BlockedApp> {
        val local = dao.getAll()
        val dashboardPackages = dashboardBlockedPackages()
        if (dashboardPackages.isEmpty()) return local

        val localByPackage = local.associateBy { it.packageName }
        val packages = localByPackage.keys + dashboardPackages
        return packages.map { pkg ->
            if (pkg in dashboardPackages) BlockedApp(packageName = pkg, blocked = true) else localByPackage.getValue(pkg)
        }
    }

    /** True if [packageName]'s block currently comes from the dashboard -- lets on-device UI hide
     *  or relabel an Undisable button that wouldn't stick past the next [reapplyAll]. */
    fun isDashboardManaged(packageName: String): Boolean = packageName in dashboardBlockedPackages()

    private fun dashboardBlockedPackages(): Set<String> {
        val entries = DashboardConfigStore(context).snapshot()?.optJSONArray("blockedApps") ?: return emptySet()
        return (0 until entries.length())
            .mapNotNull { entries.optJSONObject(it)?.optString("appId") }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** Persists the choice and applies it immediately. Returns true if the system accepted it. */
    suspend fun setBlocked(packageName: String, blocked: Boolean): Boolean {
        dao.upsert(BlockedApp(packageName, blocked))
        PackageBlockEnforcer.setBlocked(context, packageName, blocked)
        return true
    }

    /**
     * Blocks [packageName] until [durationMillis] from now, then auto-clears -- used by the
     * visual filter (see FocusGuardAccessibilityService.kt) for a 15-minute block after an NSFW
     * screenshot detection. Purely local/Room, same as [setBlocked]; never interacts with the
     * dashboard additive-union merge in [blockedApps]. The expiry itself is enforced lazily in
     * [reapplyAll] (called by both the 5-minute foreground-service loop and the 15-minute
     * WorkManager backup), not by a separate timer -- worst-case latency past the deadline is
     * whichever of those two next runs.
     */
    suspend fun blockTemporarily(packageName: String, durationMillis: Long) {
        val until = System.currentTimeMillis() + durationMillis
        dao.upsert(BlockedApp(packageName, blocked = true, blockedUntilMillis = until))
        PackageBlockEnforcer.setBlocked(context, packageName, blocked = true)
    }

    suspend fun remove(packageName: String) {
        dao.delete(packageName)
        PackageBlockEnforcer.setBlocked(context, packageName, blocked = false)
    }

    /**
     * Call on boot / Device Owner enable so the persisted list survives a reboot. Also detects
     * drift before blindly reapplying -- if a blocked app is found *not* currently blocked (e.g.
     * via `adb shell pm enable`, a fallback PackageBlockEnforcer's own doc comment names), that's
     * someone getting around a block, not just routine reapplication, so it alerts.
     */
    suspend fun reapplyAll() {
        val dashboardPackages = dashboardBlockedPackages()
        val now = System.currentTimeMillis()
        blockedApps().forEach { entry ->
            // A temporary (visual-filter) block that's past its deadline is cleared entirely --
            // it has no reason to leave a permanent Room row behind once it expires -- and skips
            // straight to the next entry rather than falling through to the drift-check/reapply
            // logic below, which would otherwise immediately re-suspend it.
            if (entry.blockedUntilMillis != null && entry.blockedUntilMillis <= now) {
                dao.delete(entry.packageName)
                PackageBlockEnforcer.setBlocked(context, entry.packageName, blocked = false)
                return@forEach
            }
            if (entry.blocked && !isCurrentlyBlocked(entry.packageName)) {
                runCatching {
                    TamperEventLogger(context).log(
                        type = "APP_BLOCK_DRIFT",
                        details = "${labelFor(entry.packageName)} was unblocked outside the app, restored",
                        debounceKey = "APP_BLOCK_DRIFT|${entry.packageName}",
                    )
                }
            }
            // A dashboard-managed block must survive an on-device Undisable tap -- see this
            // class's Phase 7 doc. Clearing the exemption here (before setBlocked) is what makes
            // the immediately-following call actually re-suspend instead of silently no-op'ing on
            // PackageBlockEnforcer's isExempt() check.
            if (entry.blocked && entry.packageName in dashboardPackages) {
                PackageDisableStore(context).markBlocked(entry.packageName)
            }
            PackageBlockEnforcer.setBlocked(context, entry.packageName, entry.blocked)
        }
    }

    suspend fun releaseAll() {
        blockedApps().forEach { PackageBlockEnforcer.setBlocked(context, it.packageName, blocked = false) }
    }

    private fun isCurrentlyBlocked(packageName: String): Boolean {
        val suspended = runCatching { pm.isPackageSuspended(packageName) }.getOrDefault(false)
        if (suspended) return true
        val hidden = dpm?.let { runCatching { it.isApplicationHidden(admin, packageName) }.getOrDefault(false) } ?: false
        if (hidden) return true
        val state = runCatching { pm.getApplicationEnabledSetting(packageName) }.getOrNull() ?: return false
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    private fun labelFor(packageName: String): String = runCatching {
        val info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
