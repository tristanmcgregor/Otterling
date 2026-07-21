package au.com.tbmcgregor.bwparker.familyguard.monitoring

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AppUsageSessionDao {
    @Insert
    suspend fun insertAll(sessions: List<AppUsageSession>)

    @Query("DELETE FROM app_usage_sessions WHERE startedAtMillis >= :sinceMillis")
    suspend fun deleteSince(sinceMillis: Long)

    @Query("DELETE FROM app_usage_sessions WHERE startedAtMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)

    @Query(
        "SELECT * FROM app_usage_sessions " +
            "WHERE startedAtMillis >= :sinceMillis ORDER BY startedAtMillis DESC",
    )
    suspend fun since(sinceMillis: Long): List<AppUsageSession>
}
