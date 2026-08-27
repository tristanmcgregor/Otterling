package app.otterling.monitoring

import android.content.Context
import android.provider.Settings
import android.util.Log
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.content.CloudFilterSettings
import app.otterling.content.MitmExemptManager
import app.otterling.content.PinningFailureTracker
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Uploads this device's own recent logcat output, plus a snapshot of its MITM-exemption state, to
 * filter-server's `/device-logs/upload` (see `lockprofile_service.py`) -- so a Guardian debugging
 * "this app still doesn't work" can see what actually happened on-device (auto-exempt attempts,
 * pinning-heuristic matches, etc.) without needing ADB access to the phone itself.
 *
 * Reuses the same `LOCKPROFILE_TOKEN` already configured for Mac-tamper polling
 * ([MacTamperPollSettings]) rather than provisioning a second credential -- same reasoning as that
 * class's own doc comment.
 */
object DeviceLogUploader {
    suspend fun upload(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        // Only autoExemptCount() (a plain SharedPreferences read) is called below -- never
        // recordSuspectedFailure -- so alertScope is never actually launched into; this
        // withContext's own scope is just a harmless, correctly-shaped value to satisfy the
        // constructor.
        val ioScope = this
        runCatching {
            val settings = MacTamperPollSettings(context)
            check(settings.isConfigured()) { "Server token not configured (see Mac tamper alerts settings)" }

            val host = CloudFilterSettings(context).host()
            check(host.isNotBlank()) { "Filter server host not configured" }

            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown-device"
            val exemptManager = MitmExemptManager(context)
            val tracker = PinningFailureTracker(context, ioScope)

            val header = buildString {
                appendLine("device_id=$deviceId")
                appendLine("uploaded_at_millis=${System.currentTimeMillis()}")
                appendLine("auto_exempt_count=${tracker.autoExemptCount()}")
                appendLine("exempt_packages=${exemptManager.exemptPackages().sorted().joinToString()}")
                appendLine("--- logcat (this app's own process only) ---")
            }
            val logs = header + DebugLogReader.recentLines(MAX_LOG_LINES).joinToString("\n")

            val requestBody = JSONObject()
                .put("device_id", deviceId)
                .put("logs", logs)
                .toString()

            val connection = URL("https://$host/device-logs/upload").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Bearer ${settings.token()}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                check(code in 200..299) { "Upload failed: HTTP $code" }
            } finally {
                connection.disconnect()
            }
        }.onFailure { error ->
            Log.w(TAG, "Log upload failed", error)
        }
    }

    private const val TAG = "DeviceLogUploader"
    private const val MAX_LOG_LINES = 2000
}
