package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager
import au.com.tbmcgregor.bwparker.familyguard.content.VpnFilterManager
import au.com.tbmcgregor.bwparker.familyguard.focus.BudgetEnforcer
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleManager
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardAppManager
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardLedgerManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.AppUninstallGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.CompanionAppGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.PackageDisableStore
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger

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
            VpnFilterManager(context).disable()
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
        CompanionAppGuard.clearUserControlLocks(context)

        TamperEventLogger(context).log(
            type = "PROTECTION_OFF",
            details = "Master protection was turned off",
        )
    }

    suspend fun startup() {
        setEnabled(true)
        DeviceRestrictionsManager(context).reapplyDesiredFromPreferences()
        au.com.tbmcgregor.bwparker.familyguard.alerts.SmsPermissionGranter.grantSendSms(context)
        CompanionAppGuard.reapplyAll(context)
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
    }
}
