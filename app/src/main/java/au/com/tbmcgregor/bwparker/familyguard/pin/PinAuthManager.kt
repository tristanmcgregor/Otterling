@file:Suppress("DEPRECATION")

package au.com.tbmcgregor.bwparker.familyguard.pin

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Gates the Settings screen behind a PIN. Only a salted PBKDF2 hash is retained, inside
 * [EncryptedSharedPreferences]; the PIN itself is never stored.
 */
class PinAuthManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        migrateLegacyPlaintextPinIfNeeded(context)
    }

    /**
     * An earlier build stored the PIN hash in a plain (unencrypted) `SharedPreferences` file
     * under this same name. `EncryptedSharedPreferences` encrypts key names as well as values, so
     * it can't see those old plaintext entries -- [hasPin] looks empty and users get dropped back
     * into the "create a PIN" flow even though their PIN is still sitting on disk. Copy it over
     * once, then scrub the plaintext copy so it doesn't linger unencrypted.
     */
    private fun migrateLegacyPlaintextPinIfNeeded(context: Context) {
        if (hasPin()) return
        val legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacySalt = legacyPrefs.getString(KEY_SALT, null)
        val legacyHash = legacyPrefs.getString(KEY_HASH, null)
        if (legacySalt != null && legacyHash != null) {
            prefs.edit()
                .putString(KEY_SALT, legacySalt)
                .putString(KEY_HASH, legacyHash)
                .apply()
        }
        if (legacyPrefs.contains(KEY_SALT) || legacyPrefs.contains(KEY_HASH)) {
            legacyPrefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
        }
    }

    fun hasPin(): Boolean = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltEncoded = prefs.getString(KEY_SALT, null) ?: return false
        val expectedHashEncoded = prefs.getString(KEY_HASH, null) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        val expectedHash = Base64.decode(expectedHashEncoded, Base64.NO_WRAP)
        return hash(pin, salt).contentEquals(expectedHash)
    }

    fun clearPin() {
        prefs.edit().clear().apply()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private companion object {
        const val PREFS_NAME = "pin_auth"
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val SALT_LENGTH_BYTES = 16
        const val ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256
    }
}
