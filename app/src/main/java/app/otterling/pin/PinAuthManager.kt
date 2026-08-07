@file:Suppress("DEPRECATION")

package app.otterling.pin

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Gates the Settings screen behind a PIN. Only a salted PBKDF2 hash is retained, inside
 * [EncryptedSharedPreferences]; the PIN itself is never stored.
 *
 * Every operation is wrapped to recover from a corrupted/undecryptable prefs file (observed as
 * `SecurityException: Could not decrypt key` from Tink) instead of crashing: this can happen if
 * the Keystore-backed master key and the on-disk encrypted file ever fall out of sync (e.g. an
 * interrupted write). Since a corrupt file can't be trusted anyway, recovery just wipes it and
 * starts fresh -- functionally equivalent to no PIN being set yet, which is the safe default.
 */
class PinAuthManager(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    // Must recover-on-failure here too, not just in `safely{}` below: this runs at field-init
    // time, before `prefs` exists, so a corrupt/undecryptable file at construction time (the
    // exact scenario this class is meant to survive) used to throw straight out of the
    // PinAuthManager constructor -- which is built inside a Compose `remember{}`, so it crashed
    // app startup on every launch until the file was manually cleared. On a Device Owner app
    // that's a serious lockout, not just an inconvenience.
    private var prefs: SharedPreferences = createPrefsOrRecover()

    init {
        migrateLegacyPlaintextPinIfNeeded(context)
    }

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

    /** Wipes the (corrupt) prefs file and returns a freshly created one. */
    private fun deleteAndRecreate(error: Exception): SharedPreferences {
        Log.e(TAG, "PIN prefs file undecryptable, resetting to no-PIN state", error)
        context.deleteSharedPreferences(PREFS_NAME)
        return createPrefs()
    }

    /** Wipes the (corrupt) prefs file and reopens a fresh one -- called on any decrypt failure
     * after construction (see [createPrefsOrRecover] for the construction-time equivalent). */
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
            safely(Unit) {
                prefs.edit()
                    .putString(KEY_SALT, legacySalt)
                    .putString(KEY_HASH, legacyHash)
                    .apply()
            }
        }
        if (legacyPrefs.contains(KEY_SALT) || legacyPrefs.contains(KEY_HASH)) {
            legacyPrefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()
        }
    }

    fun hasPin(): Boolean = safely(false) { prefs.contains(KEY_HASH) }

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        safely(Unit) {
            prefs.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .apply()
        }
    }

    /**
     * True only if [pin] is correct AND no lockout is currently in effect. Unlimited guessing
     * used to be allowed here -- a 4-8 digit PIN is only ~10,000-100,000,000 combinations, which
     * is a feasible on-device brute force with no rate limit at all. Failed attempts now trigger
     * an escalating lockout (see [recordFailedAttempt]); a successful verify clears it.
     */
    fun verifyPin(pin: String): Boolean {
        if (lockoutRemainingMillis() > 0) return false
        val correct = safely(false) {
            val saltEncoded = prefs.getString(KEY_SALT, null) ?: return@safely false
            val expectedHashEncoded = prefs.getString(KEY_HASH, null) ?: return@safely false
            val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
            val expectedHash = Base64.decode(expectedHashEncoded, Base64.NO_WRAP)
            hash(pin, salt).contentEquals(expectedHash)
        }
        if (correct) resetFailedAttempts() else recordFailedAttempt()
        return correct
    }

    /** How long the caller must still wait before another [verifyPin] attempt is honored, or 0
     * if none is in effect. Lets the UI show "try again in Ns" instead of a bare rejection. */
    fun lockoutRemainingMillis(): Long = safely(0L) {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Escalating lockout once [LOCKOUT_THRESHOLD] wrong PINs have been entered in a row: starts
     * short ([BASE_LOCKOUT_MS]) and doubles per additional failure up to [MAX_LOCKOUT_MS], same
     * shape as Android's own lockscreen backoff. */
    private fun recordFailedAttempt() {
        safely(Unit) {
            val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val lockoutMs = if (attempts >= LOCKOUT_THRESHOLD) {
                val doublings = (attempts - LOCKOUT_THRESHOLD).coerceAtMost(MAX_LOCKOUT_DOUBLINGS)
                (BASE_LOCKOUT_MS shl doublings).coerceAtMost(MAX_LOCKOUT_MS)
            } else {
                0L
            }
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, attempts)
                .putLong(KEY_LOCKOUT_UNTIL, if (lockoutMs > 0) System.currentTimeMillis() + lockoutMs else 0L)
                .apply()
        }
    }

    private fun resetFailedAttempts() {
        safely(Unit) { prefs.edit().remove(KEY_FAILED_ATTEMPTS).remove(KEY_LOCKOUT_UNTIL).apply() }
    }

    fun clearPin() {
        safely(Unit) { prefs.edit().clear().apply() }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private companion object {
        const val TAG = "PinAuthManager"
        const val PREFS_NAME = "pin_auth"
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val SALT_LENGTH_BYTES = 16
        const val ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"
        const val LOCKOUT_THRESHOLD = 5
        const val BASE_LOCKOUT_MS = 5_000L
        const val MAX_LOCKOUT_DOUBLINGS = 5
        val MAX_LOCKOUT_MS = 5 * 60 * 1000L
    }
}
