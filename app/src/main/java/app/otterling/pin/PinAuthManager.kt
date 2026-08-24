package app.otterling.pin

import android.content.Context
import android.util.Log
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.content.CloudFilterSettings
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Verifies the Settings-screen Guardian PIN server-side -- see [app.otterling.ui.PinLockScreen]
 * and `lockprofile_service.py`'s `POST /dashboard-api/pin/verify`.
 *
 * This used to fetch the plaintext PIN from `GET /dashboard-api/pin` (via the same
 * LOCKPROFILE_TOKEN bearer this app ships with) and hash-verify it locally with PBKDF2 +
 * Keystore. That broke the entire point of a Guardian PIN: LOCKPROFILE_TOKEN is embedded in the
 * shipped APK and trivially extractable by the same person the PIN is meant to gate, so they
 * could call that GET directly and read the real PIN in plaintext -- no local brute force
 * needed. Shipping down a *hash* instead wouldn't have helped either: a 4-digit PIN is only
 * 10,000 combinations, trivial to brute force offline against a leaked hash regardless of how
 * slow the KDF is. The only sound fix for a PIN this weak, given a bearer token that isn't
 * actually secret from the attacker, is server-side comparison with server-side rate limiting --
 * the device submits one guess at a time over the network and learns nothing beyond
 * correct-or-not, exactly like a SIM PIN or ATM PIN. See lockprofile_service.py's
 * `_pin_verify_record_result` for the matching server-side lockout.
 *
 * Trade-off: verifying a PIN now requires connectivity. That's intentional -- this project's
 * standing rule (see [app.otterling.content.DashboardConfigStore]'s doc comments) is fail toward
 * more restrictive, never less, and "Settings is unreachable until you're back online" is
 * strictly more restrictive than the plaintext-leak-plus-fail-open bypass this replaces.
 */
class PinAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class VerifyResult { CORRECT, INCORRECT, NO_PIN_SET, LOCKED_OUT, NETWORK_ERROR }

    data class VerifyOutcome(val result: VerifyResult, val retryAfterMs: Long = 0L)

    /** Best-effort local cache of whether the dashboard currently has a PIN configured, refreshed
     *  by [app.otterling.content.DashboardConfigStore] on its normal sync cadence via `GET
     *  /dashboard-api/pin/exists` (a boolean, never the PIN itself -- safe to cache in plain
     *  SharedPreferences). Defaults to `true` (assume a PIN exists, gate Settings) when never
     *  fetched, so a fresh install or a device that hasn't synced yet fails CLOSED -- the
     *  opposite of the old "no PIN yet, let the caller straight through" default this replaces. */
    fun cachedHasPin(): Boolean = prefs.getBoolean(KEY_HAS_PIN_CACHED, true)

    fun setCachedHasPin(hasPin: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_PIN_CACHED, hasPin).apply()
    }

    /** Blocking network call -- caller must invoke this off the main thread (e.g.
     *  `withContext(Dispatchers.IO)` from a Compose coroutine scope). */
    fun verify(pin: String): VerifyOutcome {
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured()) return VerifyOutcome(VerifyResult.NETWORK_ERROR)
        val host = CloudFilterSettings(appContext).host()
        if (host.isBlank()) return VerifyOutcome(VerifyResult.NETWORK_ERROR)

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://$host/dashboard-api/pin/verify").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer ${settings.token()}")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(JSONObject().put("pin", pin).toString().toByteArray()) }

            val code = connection.responseCode
            if (code == 429) {
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                val retryAfterMs = runCatching { JSONObject(body).optLong("retryAfterMs", 0L) }.getOrDefault(0L)
                return VerifyOutcome(VerifyResult.LOCKED_OUT, retryAfterMs)
            }
            if (code !in 200..299) return VerifyOutcome(VerifyResult.NETWORK_ERROR)

            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val hasPin = json.optBoolean("hasPin", true)
            setCachedHasPin(hasPin)
            when {
                !hasPin -> VerifyOutcome(VerifyResult.NO_PIN_SET)
                json.optBoolean("correct", false) -> VerifyOutcome(VerifyResult.CORRECT)
                else -> VerifyOutcome(VerifyResult.INCORRECT)
            }
        } catch (error: Exception) {
            Log.w(TAG, "PIN verify failed", error)
            VerifyOutcome(VerifyResult.NETWORK_ERROR)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "PinAuthManager"
        const val PREFS_NAME = "pin_auth"
        const val KEY_HAS_PIN_CACHED = "has_pin_cached"
    }
}
