package app.otterling.alerts

import android.content.Context
import android.util.Log
import app.otterling.content.CloudFilterSettings
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Local cache of `filter-server/report_types.json` (see that file's "_readme"), fetched from
 * `GET /report-config`. Lets [AlertReporter.report] honor the same hand-edited enable/disable list
 * as the server does for mac/server-origin types, for the Android-origin types (WATCHED_APP,
 * ACCESSIBILITY_DISABLED, etc.) that never touch the server at all -- without this, the phone
 * would have no way to know about a type someone disabled in that file.
 *
 * Refreshed from [MacTamperPollWorker]'s existing ~15-minute cadence rather than a dedicated
 * worker -- one more network call piggybacked on a poll that's already happening. A type missing
 * from the cache (never fetched yet, or added to the server's file after the last fetch) defaults
 * to enabled, matching the server's own "unlisted type defaults to enabled" rule -- a stale/absent
 * cache must fail toward reporting, not toward silently going deaf.
 */
class ReportConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(type: String): Boolean = prefs.getBoolean(KEY_PREFIX + type, true)

    /** Best-effort: a failed fetch just leaves the existing cache (or the all-enabled default) in
     *  place rather than throwing, so a network hiccup during [MacTamperPollWorker]'s poll never
     *  blocks or fails that poll over this. */
    fun refresh() {
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured()) return
        val host = CloudFilterSettings(appContext).host()
        if (host.isBlank()) return
        try {
            val response = httpGet("https://$host/report-config", settings.token())
            val types = JSONObject(response).optJSONObject("types") ?: return
            val editor = prefs.edit()
            val keys = types.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                editor.putBoolean(KEY_PREFIX + key, types.optBoolean(key, true))
            }
            editor.apply()
        } catch (error: Exception) {
            Log.w(TAG, "report-config fetch failed", error)
        }
    }

    private fun httpGet(urlString: String, token: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Connection", "close")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private companion object {
        const val TAG = "ReportConfigStore"
        const val PREFS = "report_config_cache"
        const val KEY_PREFIX = "enabled_"
    }
}
