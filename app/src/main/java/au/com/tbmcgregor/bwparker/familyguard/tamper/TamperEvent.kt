package au.com.tbmcgregor.bwparker.familyguard.tamper

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tamper_events")
data class TamperEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long = System.currentTimeMillis(),
    val type: String,
    val details: String,
)
