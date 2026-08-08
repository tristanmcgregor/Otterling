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
    // Always set for entries created going forward (the accountability partner's number) -- null
    // only appears on a legacy entry queued back when a separate Guardian recipient existed, and
    // is drained (not sent) rather than resolved, since that recipient has been retired. See
    // AlertReporter.flushOutbox().
    val recipientOverride: String? = null,
)
