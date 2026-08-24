package app.otterling.content

import android.content.Context
import android.util.Log
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.ui.loadInstalledApps
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reports this phone's installed-app list to `POST /dashboard-api/devices/<id>/installed-apps`
 * (see lockprofile_service.py's route comment) so the dashboard's Habit Rule Wizard can search
 * real installed apps instead of a hardcoded common-apps list or requiring the guardian to type
 * an exact package name from memory. Reuses [loadInstalledApps] -- the same enumeration the
 * on-device exempt-app/bypass-app pickers already use -- so "what the dashboard can search" and
 * "what the guardian can pick locally" stay the same list, not two independently-maintained ones.
 *
 * Wholesale-replaces the server's copy on every report (see that route's comment) -- an
 * uninstalled app should disappear from the dashboard's search too, not linger. Best-effort, same
 * stance as every other piggyback on [app.otterling.alerts.MacTamperPollWorker]'s cycle: a failed
 * report never blocks or fails that cycle over this.
 */
class InstalledAppsReporter(private val context: Context) {
    suspend fun report() = withContext(Dispatchers.IO) {
        val settings = MacTamperPollSettings(context)
        if (!settings.isConfigured()) return@withContext
        val host = CloudFilterSettings(context).host()
        if (host.isBlank()) return@withContext

        try {
            val apps = loadInstalledApps(context)
            val appsJson = JSONArray()
            for (app in apps) {
                appsJson.put(JSONObject().put("id", app.packageName).put("name", app.label))
            }
            val body = JSONObject().put("apps", appsJson).toString()

            val deviceId = DashboardConfigStore(context).deviceId()
            val connection = URL("https://$host/dashboard-api/devices/$deviceId/installed-apps")
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
                Log.w(TAG, "installed-apps report got HTTP $code")
            } else {
                Log.i(TAG, "reported ${apps.size} installed apps")
            }
        } catch (error: Exception) {
            Log.w(TAG, "installed-apps report failed", error)
        }
    }

    private companion object {
        const val TAG = "InstalledAppsReporter"
    }
}
