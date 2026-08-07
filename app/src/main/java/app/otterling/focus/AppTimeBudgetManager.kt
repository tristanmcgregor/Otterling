package app.otterling.focus

import android.content.Context
import app.otterling.data.AppDatabase
import java.time.LocalDate

/**
 * Per-app daily foreground-time budgets, with an optional stricter sub-limit for an in-app
 * feature detected heuristically by [FocusGuardAccessibilityService] (e.g. YouTube Shorts within
 * the YouTube app). Counters are ticked by the accessibility service, not this class.
 */
class AppTimeBudgetManager(context: Context) {
    private val budgetDao = AppDatabase.getInstance(context).appTimeBudgetDao()
    private val counterDao = AppDatabase.getInstance(context).appUsageCounterDao()

    suspend fun budgets(): List<AppTimeBudget> = budgetDao.getAll()

    suspend fun budget(packageName: String): AppTimeBudget? = budgetDao.get(packageName)

    suspend fun setBudget(
        packageName: String,
        dailyLimitMinutes: Int,
        subLimitMinutes: Int? = null,
        subLimitLabel: String? = null,
    ) {
        budgetDao.upsert(AppTimeBudget(packageName, dailyLimitMinutes, subLimitMinutes, subLimitLabel))
    }

    suspend fun removeBudget(packageName: String) = budgetDao.delete(packageName)

    suspend fun todayCounter(packageName: String): AppUsageCounter =
        counterDao.get(packageName, todayEpochDay()) ?: AppUsageCounter(packageName, todayEpochDay())

    /** Adds one tick of foreground time; [inSubFeature] additionally counts toward the sub-limit. */
    suspend fun addTick(packageName: String, seconds: Int, inSubFeature: Boolean): AppUsageCounter {
        val current = todayCounter(packageName)
        val updated = current.copy(
            totalSeconds = current.totalSeconds + seconds,
            subSeconds = current.subSeconds + if (inSubFeature) seconds else 0,
        )
        counterDao.upsert(updated)
        return updated
    }

    suspend fun isOverBudget(packageName: String): Boolean {
        val budget = budgetDao.get(packageName) ?: return false
        val counter = todayCounter(packageName)
        val overTotal = counter.totalSeconds >= budget.dailyLimitMinutes * 60
        val overSub = budget.subLimitMinutes?.let { counter.subSeconds >= it * 60 } ?: false
        return overTotal || overSub
    }

    suspend fun pruneOldCounters() = counterDao.deleteOlderThan(todayEpochDay() - 7)

    companion object {
        fun todayEpochDay(): Long = LocalDate.now().toEpochDay()
    }
}
