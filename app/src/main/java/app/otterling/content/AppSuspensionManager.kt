package app.otterling.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.otterling.admin.DeviceAdminReceiverImpl
import app.otterling.data.AppDatabase
import app.otterling.data.BlockedApp
import app.otterling.restrictions.PackageBlockEnforcer
import app.otterling.tamper.TamperEventLogger

/**
 * Blocks apps via package suspend (or disable-user fallback for device-admin apps) and persists
 * the chosen list so it can be re-applied after a reboot. Requires Device Owner.
 */
class AppSuspensionManager(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).blockedAppDao()
    private val pm = context.packageManager
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    suspend fun blockedApps(): List<BlockedApp> = dao.getAll()

    /** Persists the choice and applies it immediately. Returns true if the system accepted it. */
    suspend fun setBlocked(packageName: String, blocked: Boolean): Boolean {
        dao.upsert(BlockedApp(packageName, blocked))
        PackageBlockEnforcer.setBlocked(context, packageName, blocked)
        return true
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
        dao.getAll().forEach { entry ->
            if (entry.blocked && !isCurrentlyBlocked(entry.packageName)) {
                runCatching {
                    TamperEventLogger(context).log(
                        type = "APP_BLOCK_DRIFT",
                        details = "${labelFor(entry.packageName)} was unblocked outside the app, restored",
                        debounceKey = "APP_BLOCK_DRIFT|${entry.packageName}",
                    )
                }
            }
            PackageBlockEnforcer.setBlocked(context, entry.packageName, entry.blocked)
        }
    }

    suspend fun releaseAll() {
        dao.getAll().forEach { PackageBlockEnforcer.setBlocked(context, it.packageName, blocked = false) }
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
