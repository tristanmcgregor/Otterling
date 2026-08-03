package au.com.tbmcgregor.bwparker.familyguard.alerts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface AlertEventDao {
    @Insert
    suspend fun insert(event: AlertEvent): Long

    @Query("SELECT * FROM alert_events ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<AlertEvent>
}

@Dao
interface SmsOutboxDao {
    @Insert
    suspend fun insert(entry: SmsOutboxEntry): Long

    @Update
    suspend fun update(entry: SmsOutboxEntry)

    @Query("SELECT * FROM sms_outbox WHERE sent = 0 ORDER BY createdAtMillis ASC LIMIT :limit")
    suspend fun pending(limit: Int = 20): List<SmsOutboxEntry>

    @Query("DELETE FROM sms_outbox WHERE sent = 1 AND createdAtMillis < :beforeMillis")
    suspend fun deleteOldSent(beforeMillis: Long)
}
