package au.com.tbmcgregor.bwparker.familyguard.monitoring

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage_sessions")
data class AppUsageSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
) {
    val durationMillis: Long
        get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0)
}
