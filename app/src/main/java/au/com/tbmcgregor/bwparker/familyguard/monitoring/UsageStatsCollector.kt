package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.app.usage.UsageStatsManager
import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import java.time.LocalDate
import java.time.ZoneId

/**
 * Polls [UsageStatsManager] for today's per-package foreground totals and persists them. Requires
 * the user to have granted Usage Access (see [UsageAccessManager]) -- without it, queries silently
 * return nothing.
 */
class UsageStatsCollector(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).appUsageStatDao()
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)

    suspend fun collectToday() {
        val usm = usageStatsManager ?: return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startOfDayMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMillis = System.currentTimeMillis()

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDayMillis, nowMillis)
            ?: return
        stats
            .filter { it.totalTimeInForeground > 0 }
            .forEach { stat ->
                dao.upsert(AppUsageStat(stat.packageName, today.toEpochDay(), stat.totalTimeInForeground))
            }
    }

    suspend fun today(): List<AppUsageStat> = dao.getForDate(LocalDate.now().toEpochDay())
}
