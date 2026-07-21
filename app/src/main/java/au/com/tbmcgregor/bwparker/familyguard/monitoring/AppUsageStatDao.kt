package au.com.tbmcgregor.bwparker.familyguard.monitoring

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppUsageStatDao {
    @Query("SELECT * FROM app_usage_stats WHERE dateEpochDay = :dateEpochDay ORDER BY totalForegroundMillis DESC")
    suspend fun getForDate(dateEpochDay: Long): List<AppUsageStat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: AppUsageStat)

    @Query("DELETE FROM app_usage_stats WHERE dateEpochDay < :beforeEpochDay")
    suspend fun deleteOlderThan(beforeEpochDay: Long)
}
