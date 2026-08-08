package app.otterling.alerts

import android.content.Context
import android.util.Log
import app.otterling.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry for accountability alerts: persists locally and optionally enqueues an SMS to the
 * guardian number and/or a separate accountability partner's number -- each independently gated
 * by its own enabled flag, phone number, and daily cap, but sharing one debounce key/timestamp
 * per event so the same incident never re-alerts either recipient within the debounce window.
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
        val severityWantsSms = shouldSms(severity)
        val guardianWantsSms = severityWantsSms && settings.isEnabled() && settings.guardianNumber().isNotBlank()
        val partnerWantsSms = severityWantsSms && partnerSettings.isEnabled() && partnerSettings.partnerNumber().isNotBlank()
        val anyWantsSms = guardianWantsSms || partnerWantsSms
        // Shared across both recipients -- debounce is a property of the underlying event (don't
        // spam about the same recurring incident), not of who's being told about it.
        val key = debounceKey ?: "$type|${details.take(80)}"
        val debounced = anyWantsSms && isDebounced(key)

        var enqueuedAny = false
        if (anyWantsSms && !debounced) {
            val body = formatBody(type, details)

            if (guardianWantsSms) {
                if (underDailyCap(settings.dailySentCount())) {
                    outboxDao.insert(SmsOutboxEntry(body = body))
                    enqueuedAny = true
                } else {
                    maybeNotifyCapReached()
                }
            }

            if (partnerWantsSms) {
                if (underDailyCap(partnerSettings.dailySentCount())) {
                    outboxDao.insert(SmsOutboxEntry(body = body, recipientOverride = partnerSettings.partnerNumber()))
                    enqueuedAny = true
                } else {
                    maybeNotifyPartnerCapReached()
                }
            }

            if (enqueuedAny) settings.setLastDebounceMillis(key, System.currentTimeMillis())
        }

        alertDao.insert(
            AlertEvent(
                type = type,
                details = details,
                severity = severity.name,
                smsEnqueued = enqueuedAny,
            ),
        )

        if (!anyWantsSms) return@withContext
        if (debounced) {
            Log.d(TAG, "Debounced SMS for $type")
            return@withContext
        }
        if (enqueuedAny) flushOutbox()
    }

    suspend fun sendTestSms(): Boolean = withContext(Dispatchers.IO) {
        SmsPermissionGranter.grantSendSms(appContext)
        val number = settings.guardianNumber()
        if (number.isBlank()) return@withContext false
        val ok = sender.send("Otterling: test alert — SMS reporting is working.", number)
        if (ok) settings.incrementDailySentCount()
        ok
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
     * Sends every pending entry to whichever recipient owns it -- [SmsOutboxEntry.recipientOverride]
     * null means the Guardian (the original single-recipient behavior), non-null means the
     * accountability partner. Each recipient's enabled flag and daily cap are checked
     * independently so one being disabled/over-cap never blocks flushing the other's queue.
     */
    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        SmsPermissionGranter.grantSendSms(appContext)
        val pending = outboxDao.pending()
        for (entry in pending) {
            val isPartner = entry.recipientOverride != null
            val destination = entry.recipientOverride ?: settings.guardianNumber()
            val recipientEnabled = if (isPartner) partnerSettings.isEnabled() else settings.isEnabled()
            if (destination.isBlank() || !recipientEnabled) continue

            val currentCount = if (isPartner) partnerSettings.dailySentCount() else settings.dailySentCount()
            if (!underDailyCap(currentCount)) {
                if (isPartner) maybeNotifyPartnerCapReached() else maybeNotifyCapReached()
                continue
            }

            val now = System.currentTimeMillis()
            // Backoff: wait 2^attempt minutes after a failure (capped).
            val backoffMs = (1L shl entry.attemptCount.coerceAtMost(6)) * 60_000L
            if (entry.attemptCount > 0 && now - entry.lastAttemptMillis < backoffMs) continue

            val ok = sender.send(entry.body, destination)
            if (ok) {
                outboxDao.update(entry.copy(sent = true, lastAttemptMillis = now, attemptCount = entry.attemptCount + 1))
                if (isPartner) partnerSettings.incrementDailySentCount() else settings.incrementDailySentCount()
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

    private fun underDailyCap(currentCount: Int): Boolean = currentCount < GuardianAlertSettings.DAILY_SMS_CAP

    private suspend fun maybeNotifyCapReached() {
        if (settings.wasCapNotifiedToday()) return
        val number = settings.guardianNumber()
        if (number.isBlank()) return
        val ok = sender.send(
            "Otterling: daily SMS cap (${GuardianAlertSettings.DAILY_SMS_CAP}) reached; further alerts logged only.",
            number,
        )
        if (ok) {
            settings.incrementDailySentCount()
            settings.markCapNotifiedToday()
        }
    }

    private suspend fun maybeNotifyPartnerCapReached() {
        if (partnerSettings.wasCapNotifiedToday()) return
        val number = partnerSettings.partnerNumber()
        if (number.isBlank()) return
        val ok = sender.send(
            "Otterling: daily SMS cap (${GuardianAlertSettings.DAILY_SMS_CAP}) reached; further alerts logged only.",
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
