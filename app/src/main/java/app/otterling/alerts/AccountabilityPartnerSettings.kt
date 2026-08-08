package app.otterling.alerts

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * A second SMS recipient alongside the Guardian, for a separate accountability partner who should
 * see the same flagged events (content flags and tamper attempts alike) in real time. Mirrors
 * [GuardianAlertSettings]'s storage pattern, but keeps its own independent daily cap so a burst of
 * alerts to one recipient can't starve the other's budget -- debounce stays shared via
 * [GuardianAlertSettings.lastDebounceMillis] since that's a property of the underlying event, not
 * of who receives it.
 */
@Suppress("DEPRECATION")
class AccountabilityPartnerSettings(context: Context) {
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

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun partnerNumber(): String = securePrefs.getString(KEY_NUMBER, "").orEmpty()

    fun setPartnerNumber(number: String) {
        securePrefs.edit().putString(KEY_NUMBER, number.trim()).apply()
    }

    fun dailySentCount(): Int {
        val today = epochDay()
        if (prefs.getLong(KEY_CAP_DAY, -1) != today) return 0
        return prefs.getInt(KEY_CAP_COUNT, 0)
    }

    fun incrementDailySentCount() {
        val today = epochDay()
        val editor = prefs.edit()
        if (prefs.getLong(KEY_CAP_DAY, -1) != today) {
            editor.putLong(KEY_CAP_DAY, today).putInt(KEY_CAP_COUNT, 1)
        } else {
            editor.putInt(KEY_CAP_COUNT, prefs.getInt(KEY_CAP_COUNT, 0) + 1)
        }
        editor.apply()
    }

    fun wasCapNotifiedToday(): Boolean {
        val today = epochDay()
        return prefs.getLong(KEY_CAP_NOTIFIED_DAY, -1) == today
    }

    fun markCapNotifiedToday() {
        prefs.edit().putLong(KEY_CAP_NOTIFIED_DAY, epochDay()).apply()
    }

    private fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L

    companion object {
        private const val SECURE_PREFS = "accountability_partner_secure"
        private const val PREFS = "accountability_partner_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NUMBER = "partner_number"
        private const val KEY_CAP_DAY = "cap_day"
        private const val KEY_CAP_COUNT = "cap_count"
        private const val KEY_CAP_NOTIFIED_DAY = "cap_notified_day"
    }
}
