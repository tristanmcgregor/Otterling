package app.otterling.focus

import android.content.Context
import app.otterling.content.DashboardConfigStore
import app.otterling.data.AppDatabase
import java.time.LocalDate

/**
 * Per-app daily foreground-time budgets, with an optional stricter sub-limit for an in-app
 * feature detected heuristically by [FocusGuardAccessibilityService] (e.g. YouTube Shorts within
 * the YouTube app). Counters are ticked by the accessibility service, not this class.
 *
 * ## Dashboard-driven budgets (Phase 4 of `dashboard/SERVER_DRIVEN_CONFIG_PLAN.md`)
 *
 * [budgets]/[budget] overlay the dashboard's `appBudgets` (keyed by `appId`, the Android package
 * name) onto whatever's in Room, field-level: the dashboard only knows about a single daily
 * minutes limit, so when both sides have an opinion on a package, the dashboard's
 * [AppTimeBudget.dailyLimitMinutes] wins but a locally-set sub-limit (an on-device-only feature
 * the dashboard schema has no concept of) is preserved rather than discarded. A dashboard entry
 * with a null limit (not yet configured on the dashboard) is treated as no opinion, not a 0
 * limit. [setBudget]/[removeBudget] only ever touch Room -- dashboard entries never get written
 * there, so unlike Phase 1/2's list managers there's no risk of a merged read leaking into a
 * local write.
 */
class AppTimeBudgetManager(context: Context) {
    private val appContext = context.applicationContext
    private val budgetDao = AppDatabase.getInstance(context).appTimeBudgetDao()
    private val counterDao = AppDatabase.getInstance(context).appUsageCounterDao()

    suspend fun budgets(): List<AppTimeBudget> {
        val local = budgetDao.getAll()
        val dashboard = dashboardLimits()
        if (dashboard.isEmpty()) return local

        val localByPackage = local.associateBy { it.packageName }
        val packages = localByPackage.keys + dashboard.keys
        return packages.mapNotNull { packageName -> merge(packageName, localByPackage[packageName], dashboard[packageName]) }
    }

    suspend fun budget(packageName: String): AppTimeBudget? =
        merge(packageName, budgetDao.get(packageName), dashboardLimits()[packageName])

    private fun merge(packageName: String, local: AppTimeBudget?, dashboardLimitMinutes: Int?): AppTimeBudget? = when {
        dashboardLimitMinutes != null && local != null -> local.copy(dailyLimitMinutes = dashboardLimitMinutes)
        dashboardLimitMinutes != null -> AppTimeBudget(packageName = packageName, dailyLimitMinutes = dashboardLimitMinutes)
        else -> local
    }

    /** Packages whose limit currently comes from the dashboard -- lets on-device UI say so
     *  instead of showing a Remove button that wouldn't survive the next dashboard fetch. */
    fun dashboardManagedPackages(): Set<String> = dashboardLimits().keys

    private fun dashboardLimits(): Map<String, Int> {
        val entries = DashboardConfigStore(appContext).snapshot()?.optJSONArray("appBudgets") ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val appId = entry.optString("appId").takeIf { it.isNotBlank() } ?: continue
            if (!entry.has("dailyLimitMinutes") || entry.isNull("dailyLimitMinutes")) continue
            result[appId] = entry.optInt("dailyLimitMinutes")
        }
        return result
    }

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
        val budget = budget(packageName) ?: return false
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
