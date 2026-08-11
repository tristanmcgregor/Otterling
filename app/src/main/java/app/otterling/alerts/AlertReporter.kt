package app.otterling.alerts

import android.content.Context
import android.util.Log
import app.otterling.data.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single entry for accountability alerts: persists locally and optionally enqueues an SMS to
 * every configured accountability-partner number (each gated by its own independent daily cap, so
 * one partner's alert volume can't crowd out another's).
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
        val partnerNumbers = partnerSettings.partnerNumbers()
        val partnerWantsSms = shouldSms(severity) && partnerSettings.isEnabled() && partnerNumbers.isNotEmpty()
        val key = debounceKey ?: "$type|${details.take(80)}"
        val debounced = partnerWantsSms && isDebounced(key)

        var enqueued = false
        if (partnerWantsSms && !debounced) {
            val body = formatBody(type, details)
            for (number in partnerNumbers) {
                if (underDailyCap(partnerSettings.dailySentCount(number))) {
                    outboxDao.insert(SmsOutboxEntry(body = body, recipientOverride = number))
                    enqueued = true
                } else {
                    maybeNotifyPartnerCapReached(number)
                }
            }
            if (enqueued) settings.setLastDebounceMillis(key, System.currentTimeMillis())
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

    /** Sends a test SMS to every configured partner number; returns how many succeeded. */
    suspend fun sendTestSmsToPartner(): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        for (number in partnerSettings.partnerNumbers()) {
            if (sendTestSms(number)) successCount++
        }
        successCount
    }

    /** Sends a test SMS to a single partner number; returns whether it was confirmed sent. */
    suspend fun sendTestSms(number: String): Boolean = withContext(Dispatchers.IO) {
        SmsPermissionGranter.grantSendSms(appContext)
        val body = "Otterling Report — Test Alert: SMS reporting is working (${timestamp()})."
        val ok = sender.send(body, number)
        if (ok) partnerSettings.incrementDailySentCount(number)
        ok
    }

    /**
     * Sends every pending entry to its recipient -- [SmsOutboxEntry.recipientOverride] is always
     * set for entries created going forward (one of the configured partner numbers). A null
     * override, or one no longer in the currently-configured list, is drained (marked sent
     * without actually sending) rather than left stuck in the outbox forever -- the former is a
     * legacy entry from before the single-recipient Guardian era, the latter a partner removed
     * after the alert was already queued.
     *
     * Serialized process-wide via [flushMutex] (a companion-object lock, shared by every
     * `AlertReporter` instance, not just this one) -- [report] can call this inline and a
     * background loop (e.g. `ProtectionEnforcementService`) can call it on its own cadence at the
     * same time; without this, two overlapping flushes could both read the same pending rows
     * before either updates them, sending the same alert twice and burning the daily SMS cap.
     */
    suspend fun flushOutbox() = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            SmsPermissionGranter.grantSendSms(appContext)
            val pending = outboxDao.pending()
            val currentNumbers = partnerSettings.partnerNumbers().toSet()
            for (entry in pending) {
                val destination = entry.recipientOverride
                if (destination == null || destination !in currentNumbers) {
                    outboxDao.update(entry.copy(sent = true, lastAttemptMillis = System.currentTimeMillis()))
                    continue
                }
                if (!partnerSettings.isEnabled()) continue

                if (!underDailyCap(partnerSettings.dailySentCount(destination))) {
                    maybeNotifyPartnerCapReached(destination)
                    continue
                }

                val now = System.currentTimeMillis()
                // Backoff: wait 2^attempt minutes after a failure (capped).
                val backoffMs = (1L shl entry.attemptCount.coerceAtMost(6)) * 60_000L
                if (entry.attemptCount > 0 && now - entry.lastAttemptMillis < backoffMs) continue

                val ok = sender.send(entry.body, destination)
                if (ok) {
                    outboxDao.update(entry.copy(sent = true, lastAttemptMillis = now, attemptCount = entry.attemptCount + 1))
                    partnerSettings.incrementDailySentCount(destination)
                } else {
                    outboxDao.update(entry.copy(lastAttemptMillis = now, attemptCount = entry.attemptCount + 1))
                }
            }
            outboxDao.deleteOldSent(System.currentTimeMillis() - 7L * 86_400_000L)
        }
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

    private suspend fun maybeNotifyPartnerCapReached(number: String) {
        if (partnerSettings.wasCapNotifiedToday(number)) return
        if (number.isBlank()) return
        val ok = sender.send(
            "Otterling Report — Daily Limit Reached: ${AccountabilityPartnerSettings.DAILY_SMS_CAP} " +
                "alerts sent today; further activity is still logged (${timestamp()}).",
            number,
        )
        if (ok) {
            partnerSettings.incrementDailySentCount(number)
            partnerSettings.markCapNotifiedToday(number)
        }
    }

    /**
     * Builds the SMS body in the style of a standalone accountability-app report: a plain-English
     * event label (not the internal `type` code), the detail, and a timestamp -- e.g.
     * `Otterling Report — Trigger Word Alert: "sex" seen in YouTube (3:45 PM)`. Raw package names
     * never reach this text; callers pass a human-readable app label in [details] already.
     */
    private fun formatBody(type: String, details: String): String {
        val label = EVENT_LABELS[type] ?: type.lowercase().replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
        val raw = "Otterling Report — $label: $details (${timestamp()})"
        return if (raw.length <= 300) raw else raw.take(297) + "..."
    }

    private fun timestamp(): String = TIME_FORMAT.format(Date())

    private companion object {
        const val TAG = "AlertReporter"

        val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.getDefault())

        val EVENT_LABELS = mapOf(
            "TRIGGER_WORD" to "Trigger Word Alert",
            "WATCHED_APP" to "Watched App Alert",
        )

        // Shared by every AlertReporter instance in the process (companion-object members are
        // per-class, not per-instance) -- see flushOutbox()'s doc comment for why this needs to be
        // process-wide, not per-instance.
        val flushMutex = Mutex()
    }
}
