package app.otterling.alerts

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_events")
data class AlertEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long = System.currentTimeMillis(),
    val type: String,
    val details: String,
    val severity: String,
    val smsEnqueued: Boolean = false,
)

@Entity(tableName = "sms_outbox")
data class SmsOutboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val body: String,
    val attemptCount: Int = 0,
    val lastAttemptMillis: Long = 0,
    val sent: Boolean = false,
    // Null = the original single-recipient behavior (send to GuardianAlertSettings.guardianNumber()).
    // Non-null = this entry is for a different recipient (e.g. the accountability partner) whose
    // own settings/cap own it -- see AlertReporter.flushOutbox().
    val recipientOverride: String? = null,
)
