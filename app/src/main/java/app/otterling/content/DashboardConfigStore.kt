package app.otterling.content

import android.content.Context
import android.provider.Settings
import android.util.Log
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.pin.PinAuthManager
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Local cache of this device's `device_settings.json` record from the guardian dashboard (see
 * `filter-server/dashboard/SERVER_DRIVEN_CONFIG_PLAN.md` -- [snapshot] is read by
 * `RestrictionPreferences`, `MitmExemptManager`, `CustomBlocklistManager`,
 * `AppTimeBudgetManager`, `AppSuspensionManager`, `HabitRuleManager`, and `GuardianAlertSettings`
 * to enforce dashboard-driven config). `GET
 * /dashboard-api/devices/<deviceId>/settings`, same [MacTamperPollSettings.token] bearer every
 * other phone->server call already uses, since Caddy injects the same `LOCKPROFILE_TOKEN` for
 * `/dashboard-api/*` server-side (see that plan doc's "Current state" section).
 *
 * [deviceId] is [Settings.Secure.ANDROID_ID], matching [app.otterling.monitoring.DeviceLogUploader]'s
 * existing pattern -- the plan's decision #4 requires one consistent device_id across every
 * phone->server call, not a new scheme invented for this store.
 *
 * Refreshed from [app.otterling.alerts.MacTamperPollWorker]'s existing ~15-minute cadence,
 * exactly like [app.otterling.alerts.ReportConfigStore] is -- avoids adding a second periodic job
 * fighting for WorkManager's 15-minute floor.
 */
class DashboardConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Same ANDROID_ID this phone already reports itself as via `/device-logs/upload`. */
    fun deviceId(): String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    /** Last successfully fetched settings record, or null if never fetched yet. Deliberately NOT
     *  cleared on a failed [refresh] -- a network hiccup must leave the last-known-good config in
     *  place, not fall back to "unconfigured", per the plan's decision #3 (fail toward more
     *  restrictive, never less). */
    fun snapshot(): JSONObject? =
        prefs.getString(KEY_SNAPSHOT, null)?.let {
            try {
                JSONObject(it)
            } catch (error: Exception) {
                Log.w(TAG, "cached dashboard settings snapshot was invalid JSON", error)
                null
            }
        }

    fun lastFetchedAtMillis(): Long = prefs.getLong(KEY_LAST_FETCHED, 0L)

    /** Best-effort: a failed fetch just leaves the existing [snapshot] in place rather than
     *  throwing, so a network hiccup during [app.otterling.alerts.MacTamperPollWorker]'s poll
     *  never blocks or fails that poll over this. */
    fun refresh() {
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured()) return
        val host = CloudFilterSettings(appContext).host()
        if (host.isBlank()) return
        try {
            val response = httpGet(
                "https://$host/dashboard-api/devices/${deviceId()}/settings",
                settings.token(),
            )
            // Parse-validate before caching so a malformed response can't clobber the last
            // known-good snapshot with garbage.
            JSONObject(response)
            prefs.edit()
                .putString(KEY_SNAPSHOT, response)
                .putLong(KEY_LAST_FETCHED, System.currentTimeMillis())
                .apply()
            Log.i(TAG, "dashboard settings fetched for device ${deviceId()}")
        } catch (error: Exception) {
            Log.w(TAG, "dashboard settings fetch failed", error)
        }
    }

    /** Refreshes [PinAuthManager.cachedHasPin] from `GET /dashboard-api/pin/exists` (see
     *  lockprofile_service.py's GUARDIAN_PIN_PATH -- one shared PIN for the whole fleet, not
     *  per-device). Deliberately only ever touches the *existence* boolean, never the PIN value
     *  itself -- see [PinAuthManager]'s doc comment for why the plaintext PIN must never reach
     *  this device at all, let alone through plain-SharedPreferences [snapshot]/[KEY_SNAPSHOT].
     *  An actual PIN guess is checked by [PinAuthManager.verify] at entry time instead, live
     *  against the server -- this is only a cache of "does Settings need a PIN right now",
     *  refreshed on the same cadence as the rest of this class's sync. A failed fetch leaves the
     *  existing cached value in place (same fail-toward-last-known-good stance as [refresh]) --
     *  and [PinAuthManager.cachedHasPin]'s own default (`true` until the first successful fetch)
     *  already fails closed for a device that has never synced at all. */
    fun refreshPinExists() {
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured()) return
        val host = CloudFilterSettings(appContext).host()
        if (host.isBlank()) return
        try {
            val response = httpGet("https://$host/dashboard-api/pin/exists", settings.token())
            val hasPin = JSONObject(response).optBoolean("hasPin", true)
            PinAuthManager(appContext).setCachedHasPin(hasPin)
            Log.i(TAG, "guardian PIN existence synced from dashboard: hasPin=$hasPin")
        } catch (error: Exception) {
            Log.w(TAG, "guardian PIN existence sync failed", error)
        }
    }

    /** Last successfully fetched `GET /dashboard-api/habits` response (`{"habits": [{id, name,
     *  doneToday, verifiedAt}, ...]}`) -- the global habit library shared across every device,
     *  NOT part of [snapshot]/[KEY_SNAPSHOT] above (that's this one device's own settings
     *  record; habits moved out of the per-device schema entirely, see
     *  `lockprofile_service.py`'s `LIST_ENDPOINTS` comment). [HabitRuleManager] reads this for
     *  its `habitNamesById` resolution instead of the old per-device `snapshot()`. Same
     *  fail-toward-last-known-good stance as [refresh] -- a failed fetch leaves this untouched. */
    fun globalHabitsSnapshot(): JSONObject? =
        prefs.getString(KEY_HABITS_SNAPSHOT, null)?.let {
            try {
                JSONObject(it)
            } catch (error: Exception) {
                Log.w(TAG, "cached global habits snapshot was invalid JSON", error)
                null
            }
        }

    fun refreshGlobalHabits() {
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured()) return
        val host = CloudFilterSettings(appContext).host()
        if (host.isBlank()) return
        try {
            val response = httpGet("https://$host/dashboard-api/habits", settings.token())
            JSONObject(response)
            prefs.edit().putString(KEY_HABITS_SNAPSHOT, response).apply()
            Log.i(TAG, "global habits fetched")
        } catch (error: Exception) {
            Log.w(TAG, "global habits fetch failed", error)
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
        const val TAG = "DashboardConfigStore"
        const val PREFS = "dashboard_config_cache"
        const val KEY_SNAPSHOT = "settings_snapshot_json"
        const val KEY_LAST_FETCHED = "last_fetched_at_millis"
        const val KEY_HABITS_SNAPSHOT = "global_habits_snapshot_json"
    }
}
