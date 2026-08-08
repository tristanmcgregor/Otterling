package app.otterling.alerts

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Accountability-partner SMS recipient config -- an arbitrary list of numbers, one shared enabled
 * flag, but each number gets its *own* independent daily cap (so one partner's alert volume can
 * never crowd out another's budget). What counts as a flagged event (trigger words, watched apps)
 * lives separately in [GuardianAlertSettings], since that's about detection, not about who gets
 * told; debounce timestamps also stay there ([GuardianAlertSettings.lastDebounceMillis]) since
 * debounce is a property of the underlying event, not of any one recipient.
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

    init {
        migrateSingleNumberIfNeeded()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun partnerNumbers(): List<String> =
        securePrefs.getString(KEY_NUMBERS, "")
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setPartnerNumbers(numbers: List<String>) {
        securePrefs.edit().putString(KEY_NUMBERS, numbers.joinToString("\n")).apply()
    }

    fun addPartnerNumber(number: String) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return
        val current = partnerNumbers()
        if (trimmed in current) return
        setPartnerNumbers(current + trimmed)
    }

    fun removePartnerNumber(number: String) {
        setPartnerNumbers(partnerNumbers() - number)
    }

    fun dailySentCount(number: String): Int {
        val today = epochDay()
        if (prefs.getLong(capDayKey(number), -1) != today) return 0
        return prefs.getInt(capCountKey(number), 0)
    }

    fun incrementDailySentCount(number: String) {
        val today = epochDay()
        val editor = prefs.edit()
        if (prefs.getLong(capDayKey(number), -1) != today) {
            editor.putLong(capDayKey(number), today).putInt(capCountKey(number), 1)
        } else {
            editor.putInt(capCountKey(number), prefs.getInt(capCountKey(number), 0) + 1)
        }
        editor.apply()
    }

    fun wasCapNotifiedToday(number: String): Boolean {
        val today = epochDay()
        return prefs.getLong(capNotifiedDayKey(number), -1) == today
    }

    fun markCapNotifiedToday(number: String) {
        prefs.edit().putLong(capNotifiedDayKey(number), epochDay()).apply()
    }

    private fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L

    private fun capDayKey(number: String) = "cap_day_$number"
    private fun capCountKey(number: String) = "cap_count_$number"
    private fun capNotifiedDayKey(number: String) = "cap_notified_day_$number"

    /** One-time carry-over from the original single-number field into the new list, so an
     *  already-configured, already-working number isn't silently lost by this upgrade. */
    private fun migrateSingleNumberIfNeeded() {
        if (securePrefs.contains(KEY_NUMBERS)) return
        val legacy = securePrefs.getString(KEY_NUMBER_LEGACY, "").orEmpty().trim()
        if (legacy.isNotEmpty()) {
            securePrefs.edit().putString(KEY_NUMBERS, legacy).apply()
        }
    }

    companion object {
        private const val SECURE_PREFS = "accountability_partner_secure"
        private const val PREFS = "accountability_partner_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NUMBER_LEGACY = "partner_number"
        private const val KEY_NUMBERS = "partner_numbers"
        const val DAILY_SMS_CAP = 30
    }
}
