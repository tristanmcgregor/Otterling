package au.com.tbmcgregor.bwparker.familyguard.monitoring

import androidx.room.Entity

/**
 * A snapshot of a package's cumulative foreground time for one calendar day, as reported by
 * [android.app.usage.UsageStatsManager]. Overwritten (not accumulated) on every poll so it always
 * reflects the system's own daily total -- avoids double-counting from an event-based session log.
 */
@Entity(tableName = "app_usage_stats", primaryKeys = ["packageName", "dateEpochDay"])
data class AppUsageStat(
    val packageName: String,
    val dateEpochDay: Long,
    val totalForegroundMillis: Long,
)
