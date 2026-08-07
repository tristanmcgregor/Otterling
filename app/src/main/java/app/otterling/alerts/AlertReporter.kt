package app.otterling.alerts

import android.content.Context
import android.util.Log
import app.otterling.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry for accountability alerts: persists locally and optionally enqueues an SMS to the
 * guardian number (debounce + daily cap).
 */
class AlertReporter(context: Context) {
    private val appContext = context.applicationContext
    private val settings = GuardianAlertSettings(appContext)
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
        val wantsSms = shouldSms(severity) && settings.isEnabled() && settings.guardianNumber().isNotBlank()
        val key = debounceKey ?: "$type|${details.take(80)}"
        val debounced = wantsSms && isDebounced(key)
        val enqueue = wantsSms && !debounced && underDailyCap()

        alertDao.insert(
            AlertEvent(
                type = type,
                details = details,
                severity = severity.name,
                smsEnqueued = enqueue,
            ),
        )

        if (!wantsSms) return@withContext
        if (debounced) {
            Log.d(TAG, "Debounced SMS for $type")
            return@withContext
        }
        if (!underDailyCap()) {
            maybeNotifyCapReached()
            return@withContext
        }

        val body = formatBody(type, details)
        outboxDao.insert(SmsOutboxEntry(body = body))
        settings.setLastDebounceMillis(key, System.currentTimeMillis())
        flushOutbox()
    }

    suspend fun sendTestSms(): Boolean = withContext(Dispatchers.IO) {
        SmsPermissionGranter.grantSendSms(appContext)
        val number = settings.guardianNumber()
        if (number.isBlank()) return@withContext false
        val ok = sender.send("FamilyGuard: test alert — SMS reporting is working.", number)
        if (ok) settings.incrementDailySentCount()
        ok
    }

    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        SmsPermissionGranter.grantSendSms(appContext)
        val number = settings.guardianNumber()
        if (number.isBlank() || !settings.isEnabled()) return@withContext
        val pending = outboxDao.pending()
        for (entry in pending) {
            if (!underDailyCap()) {
                maybeNotifyCapReached()
                break
            }
            val now = System.currentTimeMillis()
            // Backoff: wait 2^attempt minutes after a failure (capped).
            val backoffMs = (1L shl entry.attemptCount.coerceAtMost(6)) * 60_000L
            if (entry.attemptCount > 0 && now - entry.lastAttemptMillis < backoffMs) continue

            val ok = sender.send(entry.body, number)
            if (ok) {
                outboxDao.update(entry.copy(sent = true, lastAttemptMillis = now, attemptCount = entry.attemptCount + 1))
                settings.incrementDailySentCount()
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

    private fun underDailyCap(): Boolean =
        settings.dailySentCount() < GuardianAlertSettings.DAILY_SMS_CAP

    private suspend fun maybeNotifyCapReached() {
        if (settings.wasCapNotifiedToday()) return
        val number = settings.guardianNumber()
        if (number.isBlank()) return
        val ok = sender.send(
            "FamilyGuard: daily SMS cap (${GuardianAlertSettings.DAILY_SMS_CAP}) reached; further alerts logged only.",
            number,
        )
        if (ok) {
            settings.incrementDailySentCount()
            settings.markCapNotifiedToday()
        }
    }

    private fun formatBody(type: String, details: String): String {
        val raw = "FamilyGuard: $type — $details"
        return if (raw.length <= 300) raw else raw.take(297) + "..."
    }

    private companion object {
        const val TAG = "AlertReporter"
    }
}
