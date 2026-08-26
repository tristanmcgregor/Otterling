package app.otterling.alerts

import android.content.Context
import android.os.Build
import android.util.Log
import app.otterling.data.AppDatabase
import java.text.DateFormat
import java.util.Date
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
    private val reportConfig = ReportConfigStore(appContext)

    suspend fun report(
        type: String,
        details: String,
        severity: AlertSeverity = AlertSeverity.WARNING,
        debounceKey: String? = null,
        deviceName: String? = null,
    ) = withContext(Dispatchers.IO) {
        // Fully suppressed -- no SMS, no local log entry -- for a type hand-disabled in
        // report_types.json (see ReportConfigStore). Only meaningfully applies to this phone's own
        // locally-triggered type strings (WATCHED_APP, ACCESSIBILITY_DISABLED, etc.); mac-relayed
        // types are suppressed at the server before they're ever polled, so they don't reach here
        // in the first place when disabled.
        if (!reportConfig.isEnabled(type)) {
            Log.d(TAG, "Suppressed (disabled in report_types.json): $type")
            return@withContext
        }
        val partnerNumbers = partnerSettings.partnerNumbers()
        val partnerWantsSms = shouldSms(severity) && partnerSettings.isEnabled() && partnerNumbers.isNotEmpty()
        val key = debounceKey ?: "$type|${details.take(80)}"
        val debounced = partnerWantsSms && isDebounced(key)

        var enqueued = false
        if (partnerWantsSms && !debounced) {
            val body = formatBody(type, details, deviceName)
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
        SmsPermissionGranter.grantSendSms(appContext)
        var successCount = 0
        for (number in partnerSettings.partnerNumbers()) {
            val ok = sender.send("Otterling: test alert — SMS reporting is working.", number)
            if (ok) {
                partnerSettings.incrementDailySentCount(number)
                successCount++
            }
        }
        successCount
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
            "Otterling: daily SMS cap (${AccountabilityPartnerSettings.DAILY_SMS_CAP}) reached; further alerts logged only.",
            number,
        )
        if (ok) {
            partnerSettings.incrementDailySentCount(number)
            partnerSettings.markCapNotifiedToday(number)
        }
    }

    /**
     * Compact one-line report: `Otterling Alert: "word" flagged on site (device) · 2:15 PM`.
     * [details] for a trigger-word hit is always produced in the `"<word>" seen in/on <place>`
     * shape (see `FocusGuardAccessibilityService`, macOS `FocusLockScanner`, and the server's
     * `block_reporter.py` -- all three write it identically on purpose so this one parse covers
     * every source) and gets rendered into the "flagged"-style clause below.
     *
     * Everything else is one of two buckets: [BENIGN_TYPES] (status/recovery events whose own
     * `details` text is already clear and fine to send as-is -- "content filter VPN blocked
     * example.com", "watched app opened", etc.) or a tamper event, which -- per direct request --
     * collapses to one simple, human line instead of leaking internal detail text (raw type/id
     * strings like "ACCESSIBILITY_SERVICE_DESTROYED" or a git sha mean nothing to an accountability
     * partner reading an SMS). A type not in either bucket defaults to the tamper message rather
     * than benign passthrough, so a newly-added tamper type alerts loudly by default instead of
     * silently going out as raw text.
     *
     * A guardian-set [ReportConfigStore.customMessage] for [type] (report_types.json's
     * `customMessage`, edited via the dashboard's Report Types panel) takes priority over all of
     * the above when non-blank -- `{details}` inside it is substituted with [details], same
     * placeholder convention as the server's own `_send_ntfy_notification`, so a reworded message
     * can still reference what actually happened.
     */
    private fun formatBody(type: String, details: String, deviceName: String?): String {
        val device = deviceName?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val customMessage = reportConfig.customMessage(type)
        val match = TRIGGER_WORD_DETAILS.find(details)
        val message = when {
            customMessage.isNotBlank() -> customMessage.replace("{details}", details)
            match != null -> {
                val (word, preposition, place) = match.destructured
                "\"$word\" flagged $preposition $place"
            }
            type in BENIGN_TYPES -> details
            else -> "App has been tampered with. It is highly recommended to check up on Tristan."
        }
        val raw = "Otterling Alert: $message ($device) · $time"
        return if (raw.length <= 300) raw else raw.take(297) + "..."
    }

    private companion object {
        const val TAG = "AlertReporter"

        // Matches the exact `"<word>" seen in/on <place>` shape every trigger-word source writes.
        val TRIGGER_WORD_DETAILS = Regex("^\"(.+)\" seen (in|on) (.+)$")

        // Status/recovery events, not tamper -- their own `details` text is already a clear,
        // human-readable sentence, so it's sent as-is rather than collapsed to the generic tamper
        // message. Everything NOT listed here (see formatBody's doc comment) defaults to tamper.
        val BENIGN_TYPES = setOf(
            "VPN_BLOCK", "WATCHED_APP", "UPDATE_REQUESTED", "APP_UPDATE", "HABIT_UNLOCK",
            "MAC_VPN_CLEARED", "MAC_LOCK_PROFILE_INSTALLED", "MAC_BACK", "MAC_DNS_FLOOR_REENABLED",
            // Both already-clear, self-describing sentences from TamperReporter's own details text
            // (see XPCService.killSwitch/.restoreFromKillSwitch) -- more useful shown verbatim than
            // collapsed to the generic tamper line, and CRITICAL/INFO severity (set in
            // MacTamperPollWorker.severityAndLabel) already carries the urgency either way.
            "MAC_KILL_SWITCH_ACTIVATED", "MAC_KILL_SWITCH_RESTORED",
            // Content-detection event with its own already-human-readable details text (see
            // FocusGuardAccessibilityService's reportNsfwDetection) -- not a tamper event, so it
            // must not collapse to the generic "App has been tampered with" line.
            "NSFW_SCREENSHOT_DETECTED",
        )

        // Shared by every AlertReporter instance in the process (companion-object members are
        // per-class, not per-instance) -- see flushOutbox()'s doc comment for why this needs to be
        // process-wide, not per-instance.
        val flushMutex = Mutex()
    }
}
