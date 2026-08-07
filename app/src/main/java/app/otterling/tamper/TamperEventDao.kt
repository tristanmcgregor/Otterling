package app.otterling.tamper

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TamperEventDao {
    @Insert
    suspend fun insert(event: TamperEvent)

    @Query("SELECT * FROM tamper_events ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<TamperEvent>

    @Query("SELECT * FROM tamper_events WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis DESC")
    suspend fun since(sinceMillis: Long): List<TamperEvent>
}
