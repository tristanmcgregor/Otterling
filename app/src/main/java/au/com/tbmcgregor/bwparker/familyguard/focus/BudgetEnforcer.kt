package au.com.tbmcgregor.bwparker.familyguard.focus

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.restrictions.ActiveAdminRemover
import au.com.tbmcgregor.bwparker.familyguard.restrictions.BounceBlockStore

/**
 * Suspends apps that have gone over their [AppTimeBudget] for today, and unsuspends them once a
 * new day's counter resets things. Deliberately separate from [au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager]
 * so an auto-suspend from hitting a time budget doesn't get mixed up with a manually curated
 * hard-block list in the UI.
 */
class BudgetEnforcer(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
    private val budgetManager = AppTimeBudgetManager(context)

    suspend fun reapplyAll() {
        budgetManager.budgets().forEach { budget ->
            setSuspended(budget.packageName, budgetManager.isOverBudget(budget.packageName))
        }
    }

    suspend fun releaseAll() {
        budgetManager.budgets().forEach { budget ->
            setSuspended(budget.packageName, suspended = false)
        }
    }

    private fun setSuspended(packageName: String, suspended: Boolean) {
        val dpm = devicePolicyManager ?: return
        val bounce = BounceBlockStore(context)
        if (!suspended) {
            bounce.setBlocked(packageName, blocked = false)
            runCatching { dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), false) }
            return
        }
        val failed = runCatching {
            dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
        }.getOrNull() ?: return
        if (failed.isEmpty()) {
            bounce.setBlocked(packageName, blocked = false)
            return
        }
        if (ActiveAdminRemover.suspendEvenIfAdmin(context, packageName)) {
            bounce.setBlocked(packageName, blocked = false)
        } else {
            bounce.setBlocked(packageName, blocked = true)
        }
    }
}
