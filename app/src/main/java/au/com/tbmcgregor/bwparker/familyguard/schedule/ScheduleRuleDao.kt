package au.com.tbmcgregor.bwparker.familyguard.schedule

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduleRuleDao {
    @Query("SELECT * FROM schedule_rules ORDER BY id")
    suspend fun getAll(): List<ScheduleRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: ScheduleRule): Long

    @Query("SELECT * FROM schedule_rules WHERE id = :id")
    suspend fun getById(id: Long): ScheduleRule?

    @Query("DELETE FROM schedule_rules WHERE id = :id")
    suspend fun delete(id: Long)
}
