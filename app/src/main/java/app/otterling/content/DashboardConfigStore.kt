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

    /** Pulls the guardian PIN from `GET /dashboard-api/pin` (see lockprofile_service.py's
     *  GUARDIAN_PIN_PATH -- one shared PIN for the whole fleet, not per-device) and mirrors it
     *  into [PinAuthManager] so this phone's *existing* PBKDF2 + Keystore-sealed verify path
     *  ([app.otterling.ui.PinLockScreen]) just works unchanged. Deliberately does NOT go through
     *  [snapshot]/[KEY_SNAPSHOT] -- that cache is plain unencrypted SharedPreferences, fine for
     *  website lists and app budgets but not for a raw PIN; nothing from this call is persisted
     *  outside PinAuthManager's Keystore-backed store. Re-applies setPin() on every poll rather
     *  than tracking "did it change" (a few hundred ms of PBKDF2 every ~15 minutes on a
     *  background worker is cheap, and setPin is idempotent -- simpler and more robust than
     *  comparing timestamps/hashes).
     *
     *  True mirror, both directions: [PinAuthManager.setPin] is called ONLY from here anywhere in
     *  this app (see [app.otterling.ui.PinLockScreen]'s doc comment -- there is no local
     *  create/change-PIN flow left), and if the dashboard's PIN is null (never set, or since
     *  cleared), this calls [PinAuthManager.clearPin] rather than leaving a stale local copy
     *  behind -- the one 4-digit PIN set on the website is meant to be the only one that ever
     *  exists anywhere in the Otterling ecosystem, not just the default until something else
     *  takes over. */
    fun syncPin() {
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured()) return
        val host = CloudFilterSettings(appContext).host()
        if (host.isBlank()) return
        try {
            val response = httpGet("https://$host/dashboard-api/pin", settings.token())
            val json = JSONObject(response)
            val pin = if (json.isNull("pin")) null else json.optString("pin", "")
            if (pin.isNullOrEmpty()) {
                val pinAuthManager = PinAuthManager(appContext)
                // Only touch the Keystore-backed store when there's actually something to clear
                // -- the common "dashboard has no PIN yet" case would otherwise do a pointless
                // encrypted-prefs write every ~15 minutes forever.
                if (pinAuthManager.hasPin()) pinAuthManager.clearPin()
                return
            }
            PinAuthManager(appContext).setPin(pin)
            Log.i(TAG, "guardian PIN synced from dashboard")
        } catch (error: Exception) {
            Log.w(TAG, "guardian PIN sync failed", error)
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
