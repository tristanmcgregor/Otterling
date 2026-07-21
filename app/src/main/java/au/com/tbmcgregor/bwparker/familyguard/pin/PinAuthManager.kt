package au.com.tbmcgregor.bwparker.familyguard.pin

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Gates the Settings screen behind a PIN. Stores a salted PBKDF2 hash in plain
 * [android.content.SharedPreferences] rather than `androidx.security:security-crypto` --
 * EncryptedSharedPreferences is on its way out (keyset-corruption crashes on some OEMs, and
 * Google itself now steers away from it) and isn't needed here anyway: only a one-way hash is
 * stored, never the PIN itself, so there's nothing reversible to protect at rest.
 */
class PinAuthManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
