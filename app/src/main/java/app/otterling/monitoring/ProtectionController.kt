package app.otterling.monitoring

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.otterling.admin.DeviceAdminReceiverImpl
import app.otterling.content.AppSuspensionManager
import app.otterling.content.VpnFilterManager
import app.otterling.data.AppDatabase
import app.otterling.focus.BudgetEnforcer
import app.otterling.focus.HabitRuleManager
import app.otterling.focus.RewardAppManager
import app.otterling.focus.RewardLedgerManager
import app.otterling.restrictions.AccessibilityGuard
import app.otterling.restrictions.AppUninstallGuard
import app.otterling.restrictions.DeviceRestrictionsManager
import app.otterling.restrictions.PackageDisableStore
import app.otterling.tamper.TamperEventLogger

/**
 * Master on/off for all enforcement (habit rules, budgets, VPN, suspensions, friction, tamper
 * protections, etc.). Turning off clears everything from the live system; turning back on restores
 * each feature from its saved preferences.
 */
class ProtectionController(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    suspend fun shutdown() {
        setEnabled(false)
        val vpnWasOn = VpnFilterManager(context).wasEnabledByUser()
        prefs.edit().putBoolean(KEY_VPN_WAS_ON, vpnWasOn).apply()

        ProtectionEnforcementService.stop(context)
        if (vpnWasOn) {
            // "PROTECTION_OFF" below already covers this action -- don't also send a second,
            // redundant alert specifically about the VPN.
            VpnFilterManager(context).disable(notifyIfDisabling = false)
        }

        HabitRuleManager(context).unsuspendAllTargets()
        BudgetEnforcer(context).releaseAll()
        RewardAppManager(context).setAllSuspended(suspended = false)
        AppSuspensionManager(context).releaseAll()
        PackageDisableStore(context).clearAll()

        // Also clear any leftover DPM suspensions / user-disabled packages that aren't in those
        // lists (e.g. an app suspended then removed from rules, or disabled via ADB while
        // protection was being managed). Without this, "turn protection off" leaves orphans.
        clearAllLiveBlocks()

        DeviceRestrictionsManager(context).clearAllFromSystem()
        AppUninstallGuard(context).releaseAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            runCatching { dpm?.setUserControlDisabledPackages(admin, emptyList()) }
        }

        TamperEventLogger(context).log(
            type = "PROTECTION_OFF",
            details = "Master protection was turned off",
        )
    }

    suspend fun startup() {
        setEnabled(true)
        DeviceRestrictionsManager(context).reapplyDesiredFromPreferences()
        app.otterling.alerts.SmsPermissionGranter.grantSendSms(context)
        clearLegacyCompanionProtections()
        AppUninstallGuard(context).reapplyAll()
        ProtectionEnforcementService.start(context)
        if (prefs.getBoolean(KEY_VPN_WAS_ON, false)) {
            VpnFilterManager(context).enable()
        }
        VpnFilterManager(context).reapplyIfEnabled()
        HabitRuleManager(context).reapplyAll()
        BudgetEnforcer(context).reapplyAll()
        RewardLedgerManager(context).reapply()
        AppSuspensionManager(context).reapplyAll()
    }

    /**
     * One-shot cleanup for the old Accountable2You companion shield (uninstall block, user-control
     * lock, protected-app DB row). Safe to call every startup.
     */
    private suspend fun clearLegacyCompanionProtections() {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val dao = AppDatabase.getInstance(context).protectedAppDao()
        for (pkg in LEGACY_COMPANION_PACKAGES) {
            runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
            runCatching { dao.delete(pkg) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setUserControlDisabledPackages(admin, listOf(context.packageName)) }
        }
        AccessibilityGuard.reapplyAllowlist(context)
    }

    /**
     * Unsuspends every package currently suspended, and re-enables every user (non-system) package
     * that is disabled/disabled-user. Safe to call repeatedly.
     */
    private fun clearAllLiveBlocks() {
        val pm = context.packageManager
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        val self = context.packageName

        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }.getOrElse {
            pm.getInstalledApplications(0)
        }

        val suspended = mutableListOf<String>()
        for (app in apps) {
            val pkg = app.packageName
            if (pkg == self) continue
            val isSuspended = runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(false)
            if (isSuspended) suspended += pkg

            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue
            val state = runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull() ?: continue
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                runCatching {
                    pm.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
                    Log.i(TAG, "Re-enabled disabled package $pkg")
                }.onFailure {
                    Log.w(TAG, "Could not re-enable $pkg", it)
                }
            }
        }

        if (suspended.isNotEmpty()) {
            runCatching {
                val failed = dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false)
                Log.i(TAG, "Unsuspended ${suspended.size} packages; failed=${failed.toList()}")
            }.onFailure {
                Log.e(TAG, "Bulk unsuspend failed", it)
            }
        }
    }

    private companion object {
        const val TAG = "ProtectionController"
        const val PREFS_NAME = "protection_controller"
        const val KEY_ENABLED = "enabled"
        const val KEY_VPN_WAS_ON = "vpn_was_on_before_shutdown"
        val LEGACY_COMPANION_PACKAGES = listOf(
            "com.accountable2you.ap1.googleplay",
            "com.accountable2you.reportsapp",
        )
    }
}
