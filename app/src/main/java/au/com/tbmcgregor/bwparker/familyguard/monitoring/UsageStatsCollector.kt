package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.app.usage.UsageEvents
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
    private val sessionDao = AppDatabase.getInstance(context).appUsageSessionDao()
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
        collectSessions(usm, startOfDayMillis, nowMillis)
    }

    suspend fun today(): List<AppUsageStat> = dao.getForDate(LocalDate.now().toEpochDay())

    @Suppress("DEPRECATION")
    private suspend fun collectSessions(
        usageStatsManager: UsageStatsManager,
        startMillis: Long,
        endMillis: Long,
    ) {
        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val activeStarts = mutableMapOf<String, Long>()
        val sessions = mutableListOf<AppUsageSession>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    activeStarts[event.packageName] = event.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val startedAt = activeStarts.remove(event.packageName) ?: continue
                    if (event.timeStamp > startedAt) {
                        sessions += AppUsageSession(
                            packageName = event.packageName,
                            startedAtMillis = startedAt,
                            endedAtMillis = event.timeStamp,
                        )
                    }
                }
            }
        }
        activeStarts.forEach { (packageName, startedAt) ->
            sessions += AppUsageSession(
                packageName = packageName,
                startedAtMillis = startedAt,
                endedAtMillis = endMillis,
            )
        }

        sessionDao.deleteSince(startMillis)
        if (sessions.isNotEmpty()) sessionDao.insertAll(sessions)
    }
}
