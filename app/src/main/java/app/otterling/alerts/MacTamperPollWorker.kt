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
                    reporter.report(
                        type = label,
                        details = event.optString("details", "").ifBlank { "(no details)" } +
                            " [Mac: ${event.optString("device_id", "?")}]",
                        severity = severity,
                        debounceKey = "mac_tamper|$id",
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
        "daemon_unloaded_recovered" -> AlertSeverity.WARNING to "MAC_DAEMON_RECOVERED"
        "watchdog_or_daemon_reregistered" -> AlertSeverity.WARNING to "MAC_DAEMON_REREGISTERED"
        "lock_profile_installed" -> AlertSeverity.INFO to "MAC_LOCK_PROFILE_INSTALLED"
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
