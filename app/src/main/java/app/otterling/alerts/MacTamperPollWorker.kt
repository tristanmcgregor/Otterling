package app.otterling.alerts

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.otterling.content.CloudFilterSettings
import app.otterling.content.DashboardConfigStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Polls `filter-server`'s `/alerts/poll` (see `lockprofile_service.py`) for tamper events reported
 * by the macOS daemon (`LockProfileGuard`/`FocusLockWatchdog`/`TamperReporter`) and feeds each new
 * one into [AlertReporter] -- reusing this phone's *existing* SMS pipeline
 * ([GuardianSmsSender]/[AccountabilityPartnerSettings]) rather than standing up a new provider.
 * There's no push channel to the Mac's server (no Firebase project in this app), so this is a
 * WorkManager poll on the same 15-minute floor [UpdateCheckWorker] already uses -- up to ~15
 * minutes of latency, in exchange for needing nothing new signed up for.
 *
 * Same host as the DNS/proxy cloud filter ([CloudFilterSettings.host]) -- one family server, one
 * place to configure it -- but a separate credential ([MacTamperPollSettings.token], the server's
 * `LOCKPROFILE_TOKEN`), entered once in Settings.
 */
class MacTamperPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = MacTamperPollSettings(applicationContext)
        if (!settings.isConfigured()) {
            // Nothing set up yet -- same quiet no-op stance CloudFilterSettings takes when
            // disabled, not an error worth retrying over.
            return@withContext Result.success()
        }

        val host = CloudFilterSettings(applicationContext).host()
        // Piggybacked on this same poll cadence -- see ReportConfigStore's doc comment. Best-effort
        // and independent of the poll below: a failed refresh just leaves the existing cache in
        // place, it never turns this into a Result.retry().
        ReportConfigStore(applicationContext).refresh()
        // Same best-effort piggyback as ReportConfigStore above -- see SERVER_DRIVEN_CONFIG_PLAN.md.
        DashboardConfigStore(applicationContext).refresh()
        // Same best-effort piggyback -- see DashboardConfigStore.refreshPinExists's doc comment
        // for why this is a separate call rather than folded into refresh()'s cached snapshot.
        DashboardConfigStore(applicationContext).refreshPinExists()
        // Global habit library + live completion state (shared across every device, not this
        // phone's own settings record) -- HabitRuleManager reads this on its own separate
        // ~5-minute evaluation cadence (HabitShareSyncManager), so this just needs to stay
        // reasonably fresh, not be triggered in lockstep with it.
        DashboardConfigStore(applicationContext).refreshGlobalHabits()
        val sinceId = settings.lastSeenAlertId()
        try {
            val response = httpGet("https://$host/alerts/poll?since_id=$sinceId", settings.token())
            val events = JSONObject(response).optJSONArray("events")
            val reporter = AlertReporter(applicationContext)
            if (events != null) {
                // Server already filters to id > sinceId and returns them in append (ascending id)
                // order -- persisting the cursor after each individual report(), not just once at
                // the end, means a crash/kill partway through this loop can't cause an already-
                // reported event to be re-reported on the next poll.
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val id = event.optInt("id", -1)
                    if (id <= 0) continue
                    val macType = event.optString("type", "unknown")
                    val (severity, label) = severityAndLabel(macType)
                    // device_name (a human-readable computer name, e.g. "Tristan's MacBook Pro")
                    // is optional -- older event types / the mitm proxy's block_reporter.py don't
                    // send one (it can't attribute a shared household egress IP to one device), so
                    // AlertReporter.formatBody falls back to this phone's own model name rather
                    // than showing device_id's raw UUID/IP.
                    reporter.report(
                        type = label,
                        details = event.optString("details", "").ifBlank { "(no details)" },
                        severity = severity,
                        debounceKey = "mac_tamper|$id",
                        deviceName = event.optString("device_name", "").ifBlank { null },
                    )
                    settings.setLastSeenAlertId(id)
                }
            }
            settings.setLastPolledAtMillis(System.currentTimeMillis())
            Result.success()
        } catch (error: Exception) {
            Log.w(TAG, "Mac tamper poll failed", error)
            Result.retry()
        }
    }

    /** Mirrors `NTFY_EVENT_STYLE` in `lockprofile_service.py` -- same event types, same relative
     *  urgency, different channel. Falls back to a generic WARNING for any type not listed here so
     *  a future new event type still reaches the partner instead of silently not alerting. */
    private fun severityAndLabel(macType: String): Pair<AlertSeverity, String> = when (macType) {
        "lock_profile_removed" -> AlertSeverity.CRITICAL to "MAC_LOCK_PROFILE_REMOVED"
        "vpn_active" -> AlertSeverity.CRITICAL to "MAC_VPN_ACTIVE"
        "vpn_cleared" -> AlertSeverity.INFO to "MAC_VPN_CLEARED"
        "daemon_unloaded_recovered" -> AlertSeverity.WARNING to "MAC_DAEMON_RECOVERED"
        "watchdog_or_daemon_reregistered" -> AlertSeverity.WARNING to "MAC_DAEMON_REREGISTERED"
        "lock_profile_installed" -> AlertSeverity.INFO to "MAC_LOCK_PROFILE_INSTALLED"
        // The Mac's own app binaries were built from an uncommitted, locally-modified source tree
        // -- see IntegrityReporter.swift / lockprofile_service.py's /integrity/checkin. This is the
        // direct "edited the code and installed it locally" bypass the rest of the self-lockout
        // design exists to catch, so it gets the same top severity as the VPN-bypass case.
        "mac_code_tampered" -> AlertSeverity.CRITICAL to "MAC_CODE_TAMPERED"
        // The DNS floor's filter was switched off in System Settings > Network > VPN & Filters
        // without removing the profile -- see LockProfileGuard.swift's dnsFloorFunctionallyActive().
        "dns_floor_disabled" -> AlertSeverity.CRITICAL to "MAC_DNS_FLOOR_DISABLED"
        "dns_floor_reenabled" -> AlertSeverity.INFO to "MAC_DNS_FLOOR_REENABLED"
        // SudoBroker.swift's privilege-elevation decisions -- reported for every outcome, approved
        // or denied, so a successful AI-review approval still reaches the partner (see that file's
        // doc comment for why that matters: a social-engineered approval must not go unnoticed).
        "sudo_request_approved" -> AlertSeverity.CRITICAL to "MAC_SUDO_APPROVED"
        "sudo_request_denied" -> AlertSeverity.WARNING to "MAC_SUDO_DENIED"
        "sudo_request_ai_reviewed" -> AlertSeverity.WARNING to "MAC_SUDO_AI_REVIEWED"
        // The Mac's emergency stop for the WHOLE app (DNS/proxy/pf/blocked-protected
        // apps/scanner/GUI app), not just filtering -- see XPCService.killSwitch. Top severity:
        // this is the single most protection-reducing event the Mac can report.
        "kill_switch_activated" -> AlertSeverity.CRITICAL to "MAC_KILL_SWITCH_ACTIVATED"
        "kill_switch_restored" -> AlertSeverity.INFO to "MAC_KILL_SWITCH_RESTORED"
        else -> AlertSeverity.WARNING to "MAC_TAMPER_${macType.uppercase()}"
    }

    private fun httpGet(urlString: String, token: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Connection", "close")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    companion object {
        private const val TAG = "MacTamperPollWorker"
        private const val PERIODIC_WORK_NAME = "mac_tamper_poll_periodic"
        private const val ONE_SHOT_WORK_NAME = "mac_tamper_poll_one_shot"
        private val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodic(context: Context) {
            // Same 15-minute WorkManager floor as UpdateCheckWorker.enqueuePeriodic.
            val request = PeriodicWorkRequestBuilder<MacTamperPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(NETWORK_CONSTRAINTS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Manual "Poll now" entry point from Settings, mirroring [UpdateCheckWorker.enqueueOneShot]. */
        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<MacTamperPollWorker>()
                .setConstraints(NETWORK_CONSTRAINTS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
