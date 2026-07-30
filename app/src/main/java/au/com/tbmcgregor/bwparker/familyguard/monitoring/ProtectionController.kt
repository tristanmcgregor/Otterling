package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager
import au.com.tbmcgregor.bwparker.familyguard.content.VpnFilterManager
import au.com.tbmcgregor.bwparker.familyguard.focus.BudgetEnforcer
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleManager
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardAppManager
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardLedgerManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.AppUninstallGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager

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

        DeviceRestrictionsManager(context).clearAllFromSystem()
        AppUninstallGuard(context).releaseAll()
    }

    suspend fun startup() {
        setEnabled(true)
        DeviceRestrictionsManager(context).reapplyDesiredFromPreferences()
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

    private companion object {
        const val PREFS_NAME = "protection_controller"
        const val KEY_ENABLED = "enabled"
        const val KEY_VPN_WAS_ON = "vpn_was_on_before_shutdown"
    }
}
