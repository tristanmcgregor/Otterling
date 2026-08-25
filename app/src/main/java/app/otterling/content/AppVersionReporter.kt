package app.otterling.content

import android.content.Context
import android.util.Log
import app.otterling.BuildConfig
import app.otterling.alerts.MacTamperPollSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reports this phone's app version to `POST /dashboard-api/devices/<id>/app-info` (see
 * lockprofile_service.py's route comment) so the dashboard can show a device is running an old
 * build without the guardian needing to unlock the phone and check Settings themselves. Same
 * best-effort piggyback on [app.otterling.alerts.MacTamperPollWorker]'s cycle as
 * [InstalledAppsReporter] -- a failed report never blocks or fails that cycle over this.
 */
class AppVersionReporter(private val context: Context) {
    suspend fun report() = withContext(Dispatchers.IO) {
        val settings = MacTamperPollSettings(context)
        if (!settings.isConfigured()) return@withContext
        val host = CloudFilterSettings(context).host()
        if (host.isBlank()) return@withContext

        try {
            val body = JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .toString()

            val deviceId = DashboardConfigStore(context).deviceId()
            val connection = URL("https://$host/dashboard-api/devices/$deviceId/app-info")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer ${settings.token()}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "app-info report got HTTP $code")
            } else {
                Log.i(TAG, "reported app version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
        } catch (error: Exception) {
            Log.w(TAG, "app-info report failed", error)
        }
    }

    private companion object {
        const val TAG = "AppVersionReporter"
    }
}
