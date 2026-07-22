package au.com.tbmcgregor.bwparker.familyguard.focus

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * Suspends apps that have gone over their [AppTimeBudget] for today, and unsuspends them once a
 * new day's counter resets things. Deliberately separate from [au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager]
 * so an auto-suspend from hitting a time budget doesn't get mixed up with a manually curated
 * hard-block list in the UI.
 */
class BudgetEnforcer(context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
    private val budgetManager = AppTimeBudgetManager(context)

    suspend fun reapplyAll() {
        budgetManager.budgets().forEach { budget ->
            setSuspended(budget.packageName, budgetManager.isOverBudget(budget.packageName))
        }
    }

    private fun setSuspended(packageName: String, suspended: Boolean) {
        val dpm = devicePolicyManager ?: return
        runCatching { dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), suspended) }
    }
}
