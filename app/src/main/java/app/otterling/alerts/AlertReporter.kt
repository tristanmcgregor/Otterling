package app.otterling.alerts

import android.content.Context
import android.util.Log
import app.otterling.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry for accountability alerts: persists locally and optionally enqueues an SMS to the
 * accountability partner's number (subject to its own enabled flag and daily cap).
 */
class AlertReporter(context: Context) {
    private val appContext = context.applicationContext
    private val settings = GuardianAlertSettings(appContext)
    private val partnerSettings = AccountabilityPartnerSettings(appContext)
    private val sender = GuardianSmsSender(appContext)
    private val db = AppDatabase.getInstance(appContext)
    private val alertDao = db.alertEventDao()
    private val outboxDao = db.smsOutboxDao()

    suspend fun report(
        type: String,
        details: String,
        severity: AlertSeverity = AlertSeverity.WARNING,
        debounceKey: String? = null,
    ) = withContext(Dispatchers.IO) {
        val partnerWantsSms = shouldSms(severity) && partnerSettings.isEnabled() && partnerSettings.partnerNumber().isNotBlank()
        val key = debounceKey ?: "$type|${details.take(80)}"
        val debounced = partnerWantsSms && isDebounced(key)

        var enqueued = false
        if (partnerWantsSms && !debounced) {
            if (underDailyCap(partnerSettings.dailySentCount())) {
                val body = formatBody(type, details)
                outboxDao.insert(SmsOutboxEntry(body = body, recipientOverride = partnerSettings.partnerNumber()))
                settings.setLastDebounceMillis(key, System.currentTimeMillis())
                enqueued = true
            } else {
                maybeNotifyPartnerCapReached()
            }
        }

        alertDao.insert(
            AlertEvent(
                type = type,
                details = details,
                severity = severity.name,
                smsEnqueued = enqueued,
            ),
        )

        if (!partnerWantsSms) return@withContext
        if (debounced) {
            Log.d(TAG, "Debounced SMS for $type")
            return@withContext
        }
        if (enqueued) flushOutbox()
    }

    suspend fun sendTestSmsToPartner(): Boolean = withContext(Dispatchers.IO) {
        SmsPermissionGranter.grantSendSms(appContext)
        val number = partnerSettings.partnerNumber()
        if (number.isBlank()) return@withContext false
        val ok = sender.send("Otterling: test alert — SMS reporting is working.", number)
        if (ok) partnerSettings.incrementDailySentCount()
        ok
    }

    /**
     * Sends every pending entry to its recipient -- [SmsOutboxEntry.recipientOverride] is always
     * set for entries created going forward (the accountability partner's number), but a null
     * override is still resolved against [GuardianAlertSettings] for backward compatibility with
     * any entry queued before the Guardian recipient was retired.
     */
    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        SmsPermissionGranter.grantSendSms(appContext)
        val pending = outboxDao.pending()
        for (entry in pending) {
            val destination = entry.recipientOverride
            if (destination == null) {
                // Guardian recipient retired -- nothing is queued for it going forward, but drain
                // any already-pending legacy entry rather than leaving it stuck in the outbox
                // forever (deleteOldSent below only ever removes already-sent rows).
                outboxDao.update(entry.copy(sent = true, lastAttemptMillis = System.currentTimeMillis()))
                continue
            }
            if (!partnerSettings.isEnabled()) continue

            if (!underDailyCap(partnerSettings.dailySentCount())) {
                maybeNotifyPartnerCapReached()
                continue
            }

            val now = System.currentTimeMillis()
            // Backoff: wait 2^attempt minutes after a failure (capped).
            val backoffMs = (1L shl entry.attemptCount.coerceAtMost(6)) * 60_000L
            if (entry.attemptCount > 0 && now - entry.lastAttemptMillis < backoffMs) continue

            val ok = sender.send(entry.body, destination)
            if (ok) {
                outboxDao.update(entry.copy(sent = true, lastAttemptMillis = now, attemptCount = entry.attemptCount + 1))
                partnerSettings.incrementDailySentCount()
            } else {
                outboxDao.update(entry.copy(lastAttemptMillis = now, attemptCount = entry.attemptCount + 1))
            }
        }
        outboxDao.deleteOldSent(System.currentTimeMillis() - 7L * 86_400_000L)
    }

    private fun shouldSms(severity: AlertSeverity): Boolean = when (severity) {
        AlertSeverity.CRITICAL, AlertSeverity.WARNING -> true
        AlertSeverity.INFO -> settings.smsInfoEvents()
    }

    private fun isDebounced(key: String): Boolean {
        val last = settings.lastDebounceMillis(key)
        return last > 0 && System.currentTimeMillis() - last < GuardianAlertSettings.DEBOUNCE_MS
    }

    private fun underDailyCap(currentCount: Int): Boolean = currentCount < AccountabilityPartnerSettings.DAILY_SMS_CAP

    private suspend fun maybeNotifyPartnerCapReached() {
        if (partnerSettings.wasCapNotifiedToday()) return
        val number = partnerSettings.partnerNumber()
        if (number.isBlank()) return
        val ok = sender.send(
            "Otterling: daily SMS cap (${AccountabilityPartnerSettings.DAILY_SMS_CAP}) reached; further alerts logged only.",
            number,
        )
        if (ok) {
            partnerSettings.incrementDailySentCount()
            partnerSettings.markCapNotifiedToday()
        }
    }

    private fun formatBody(type: String, details: String): String {
        val raw = "Otterling: $type — $details"
        return if (raw.length <= 300) raw else raw.take(297) + "..."
    }

    private companion object {
        const val TAG = "AlertReporter"
    }
}
