package au.com.tbmcgregor.bwparker.familyguard.alerts

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guardian SMS + broader alert settings. Phone number lives in encrypted prefs; watchlist and
 * trigger words are plain sets (not secrets).
 */
@Suppress("DEPRECATION")
class GuardianAlertSettings(context: Context) {
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

    fun guardianNumber(): String = securePrefs.getString(KEY_NUMBER, "").orEmpty()

    fun setGuardianNumber(number: String) {
        securePrefs.edit().putString(KEY_NUMBER, number.trim()).apply()
    }

    fun triggerWords(): List<String> =
        prefs.getString(KEY_TRIGGERS, "")
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setTriggerWords(raw: String) {
        prefs.edit().putString(KEY_TRIGGERS, raw).apply()
    }

    fun watchedPackages(): Set<String> =
        prefs.getStringSet(KEY_WATCHED, emptySet())?.toSet().orEmpty()

    fun setWatchedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_WATCHED, packages).apply()
    }

    fun addWatchedPackage(packageName: String) {
        setWatchedPackages(watchedPackages() + packageName)
    }

    fun removeWatchedPackage(packageName: String) {
        setWatchedPackages(watchedPackages() - packageName)
    }

    fun smsInfoEvents(): Boolean = prefs.getBoolean(KEY_SMS_INFO, false)

    fun setSmsInfoEvents(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMS_INFO, enabled).apply()
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

    fun lastDebounceMillis(key: String): Long = prefs.getLong(debounceKey(key), 0L)

    fun setLastDebounceMillis(key: String, millis: Long) {
        prefs.edit().putLong(debounceKey(key), millis).apply()
    }

    private fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L

    private fun debounceKey(key: String): String = "debounce_$key"

    companion object {
        private const val TAG = "GuardianAlertSettings"
        private const val SECURE_PREFS = "guardian_alert_secure"
        private const val PREFS = "guardian_alert_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NUMBER = "guardian_number"
        private const val KEY_TRIGGERS = "trigger_words"
        private const val KEY_WATCHED = "watched_packages"
        private const val KEY_SMS_INFO = "sms_info_events"
        private const val KEY_CAP_DAY = "cap_day"
        private const val KEY_CAP_COUNT = "cap_count"
        private const val KEY_CAP_NOTIFIED_DAY = "cap_notified_day"
        const val DAILY_SMS_CAP = 30
        const val DEBOUNCE_MS = 10 * 60_000L
    }
}
