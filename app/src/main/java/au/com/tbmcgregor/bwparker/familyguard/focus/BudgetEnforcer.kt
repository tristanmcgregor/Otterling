package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.restrictions.PackageBlockEnforcer

/**
 * Suspends apps that have gone over their [AppTimeBudget] for today, and unsuspends them once a
 * new day's counter resets things. Deliberately separate from [au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager]
 * so an auto-suspend from hitting a time budget doesn't get mixed up with a manually curated
 * hard-block list in the UI.
 */
class BudgetEnforcer(private val context: Context) {
    private val budgetManager = AppTimeBudgetManager(context)

    suspend fun reapplyAll() {
        budgetManager.budgets().forEach { budget ->
            PackageBlockEnforcer.setBlocked(
                context,
                budget.packageName,
                blocked = budgetManager.isOverBudget(budget.packageName),
            )
        }
    }

    suspend fun releaseAll() {
        budgetManager.budgets().forEach { budget ->
            PackageBlockEnforcer.setBlocked(context, budget.packageName, blocked = false)
        }
    }
}
