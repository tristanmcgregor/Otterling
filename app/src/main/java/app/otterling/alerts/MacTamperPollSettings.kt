package app.otterling.alerts

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings for polling the macOS filter-server's tamper-alert log (see
 * `filter-server/lockprofile_service.py`'s `/alerts/poll`, and [MacTamperPollWorker] which does
 * the actual polling). Token is treated as sensitive (same split as [CloudFilterSettings]'s
 * proxy credentials) since it's also valid for `/lockprofile/provision`; the poll cursor isn't a
 * secret so stays in plain prefs.
 */
@Suppress("DEPRECATION")
class MacTamperPollSettings(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val securePrefs = EncryptedSharedPreferences.create(
        appContext,
        SECURE_PREFS,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The `LOCKPROFILE_TOKEN` from the server's `.env` -- same value `install_lock_profile.py`
     *  uses on the Mac side. Only actually polls once this is set, same "off with no host/token
     *  configured" stance as [CloudFilterSettings]. */
    fun token(): String = securePrefs.getString(KEY_TOKEN, "").orEmpty()

    fun setToken(token: String) {
        securePrefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun isConfigured(): Boolean = token().isNotEmpty()

    /** Highest event `id` already turned into an [AlertReporter] call -- `MacTamperPollWorker`
     *  only ever asks the server for events after this, so nothing gets re-alerted on a retry. */
    fun lastSeenAlertId(): Int = prefs.getInt(KEY_LAST_SEEN_ID, 0)

    fun setLastSeenAlertId(id: Int) {
        prefs.edit().putInt(KEY_LAST_SEEN_ID, id).apply()
    }

    fun lastPolledAtMillis(): Long = prefs.getLong(KEY_LAST_POLLED, 0L)

    fun setLastPolledAtMillis(millis: Long) {
        prefs.edit().putLong(KEY_LAST_POLLED, millis).apply()
    }

    /** The FCM registration token this device last *successfully* handed to the filter-server, so
     *  [FcmTokenRegistrar] can skip a redundant re-POST when nothing has changed. Not a secret (it's
     *  a routing address, useless without the FCM server key), so it lives in plain prefs. */
    fun lastRegisteredFcmToken(): String = prefs.getString(KEY_FCM_TOKEN, "").orEmpty()

    fun setLastRegisteredFcmToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    private companion object {
        const val SECURE_PREFS = "mac_tamper_poll_secure"
        const val PREFS = "mac_tamper_poll_settings"
        const val KEY_TOKEN = "lockprofile_token"
        const val KEY_LAST_SEEN_ID = "last_seen_alert_id"
        const val KEY_LAST_POLLED = "last_polled_at_millis"
        const val KEY_FCM_TOKEN = "last_registered_fcm_token"
    }
}
