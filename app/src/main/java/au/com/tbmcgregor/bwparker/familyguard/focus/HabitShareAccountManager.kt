package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException

/**
 * Securely stores the user's own HabitShare login (username/password, plus the auth token once
 * issued) so [HabitShareApiClient] can poll HabitShare's own server directly for exact
 * done/not-done status instead of relying on the on-screen screenshot heuristic. Nothing here is
 * ever sent anywhere except HabitShare's own servers -- this is the same account the user already
 * signs into inside the HabitShare app itself.
 *
 * Mirrors [au.com.tbmcgregor.bwparker.familyguard.pin.PinAuthManager]'s corruption-recovery
 * pattern: any Keystore/Tink decrypt failure just wipes the file and falls back to "not
 * connected" rather than crashing.
 */
class HabitShareAccountManager(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    // Recover-on-failure here too, not just in `safely{}` below -- this runs at field-init time,
    // before `prefs` exists, so a corrupt/undecryptable file at construction time used to crash
    // whatever screen constructs this (HabitShare settings, the 30s sync loop, etc.) instead of
    // falling back to "not connected" as intended. See PinAuthManager for the identical fix.
    private var prefs: SharedPreferences = createPrefsOrRecover()

    private fun createPrefs(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun createPrefsOrRecover(): SharedPreferences = try {
        createPrefs()
    } catch (error: SecurityException) {
        deleteAndRecreate(error)
    } catch (error: GeneralSecurityException) {
        deleteAndRecreate(error)
    }

    private fun deleteAndRecreate(error: Exception): SharedPreferences {
        Log.e(TAG, "HabitShare account prefs undecryptable, resetting to disconnected state", error)
        context.deleteSharedPreferences(PREFS_NAME)
        return createPrefs()
    }

    private fun recoverFromCorruption(error: Exception) {
        prefs = deleteAndRecreate(error)
    }

    private fun <T> safely(default: T, block: () -> T): T = try {
        block()
    } catch (error: SecurityException) {
        recoverFromCorruption(error)
        default
    } catch (error: GeneralSecurityException) {
        recoverFromCorruption(error)
        default
    }

    fun isConnected(): Boolean = safely(false) { prefs.contains(KEY_USERNAME) }

    fun username(): String? = safely(null) { prefs.getString(KEY_USERNAME, null) }

    fun password(): String? = safely(null) { prefs.getString(KEY_PASSWORD, null) }

    fun token(): String? = safely(null) { prefs.getString(KEY_TOKEN, null) }

    fun saveCredentials(username: String, password: String) {
        safely(Unit) {
            prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .apply()
        }
    }

    fun saveToken(token: String) {
        safely(Unit) { prefs.edit().putString(KEY_TOKEN, token).apply() }
    }

    fun disconnect() {
        safely(Unit) { prefs.edit().clear().apply() }
    }

    private companion object {
        const val TAG = "HabitShareAccountManager"
        const val PREFS_NAME = "habitshare_account"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
    }
}
