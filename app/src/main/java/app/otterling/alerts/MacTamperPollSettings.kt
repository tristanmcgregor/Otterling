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
     *  uses on the Mac side, and the same baked-in-default tradeoff `FocusLockConstants
     *  .defaultLockProfileToken` makes there: falls back to [DEFAULT_TOKEN] so polling/FCM
     *  registration work out of the box, with no setup screen required before this phone's own
     *  accountability alerts start flowing. Embedding it means it ships inside the APK and is
     *  extractable by anyone with the binary -- originally an accepted trade since `/alerts/tamper`
     *  is append-only ingestion, so the worst a leaked token bought was posting spurious alerts or
     *  reading the alert feed, not disabling any protection. That premise no longer holds on the
     *  Mac side: once `DashboardConfigSync` shipped there, possession of this same token is also
     *  sufficient to immediately apply a Mac protection removal. See `FocusLockConstants
     *  .defaultLockProfileToken`'s comment for the full picture. The settings field still lets a
     *  different value override this (e.g. pointing at a rotated token or a different family's
     *  server). */
    fun token(): String = securePrefs.getString(KEY_TOKEN, "").orEmpty().ifBlank { DEFAULT_TOKEN }

    fun setToken(token: String) {
        securePrefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    /** Always true now that [token] falls back to [DEFAULT_TOKEN] -- kept as a named check (rather
     *  than inlining `true`) so call sites read the same as before, and so a future change back to
     *  no-default-without-setup only needs to change this one place. */
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

    /** The device_id [FcmTokenRegistrar] last successfully associated with [lastRegisteredFcmToken]
     *  server-side -- tracked separately from the token itself so an already-registered install
     *  (from before per-device FCM targeting existed) still re-POSTs once to backfill the
     *  association, even though its token hasn't changed and would otherwise look "already
     *  registered" to [FcmTokenRegistrar]'s skip check. */
    fun lastRegisteredFcmDeviceId(): String = prefs.getString(KEY_FCM_DEVICE_ID, "").orEmpty()

    fun setLastRegisteredFcmDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_FCM_DEVICE_ID, deviceId).apply()
    }

    private companion object {
        const val SECURE_PREFS = "mac_tamper_poll_secure"
        const val PREFS = "mac_tamper_poll_settings"
        const val KEY_TOKEN = "lockprofile_token"
        const val KEY_LAST_SEEN_ID = "last_seen_alert_id"
        const val KEY_LAST_POLLED = "last_polled_at_millis"
        const val KEY_FCM_TOKEN = "last_registered_fcm_token"
        const val KEY_FCM_DEVICE_ID = "last_registered_fcm_device_id"

        // Rotate this (and the server's LOCKPROFILE_TOKEN in filter-server/.env) together --
        // see the doc comment on `token()` for why this is baked in at all.
        const val DEFAULT_TOKEN = "22ff3ed0a6b843633a6499911abb7378239e6e9e6cbd97d56e465b39d0dbdc9b"
    }
}
