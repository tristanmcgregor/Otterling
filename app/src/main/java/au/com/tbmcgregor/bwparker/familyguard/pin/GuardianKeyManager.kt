package au.com.tbmcgregor.bwparker.familyguard.pin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

/**
 * RSA-OAEP/SHA-256 keypair used to claim this phone's half of a one-time Guardian setup link (see
 * `server/guardian_relay.py` and the Mac's `GuardianSetupCrypto`). The private key is generated
 * inside the Android Keystore with `setUserAuthenticationRequired(false)` but never exportable --
 * only Keystore-mediated decrypt operations can use it, so the PIN ciphertext can only ever be
 * unwrapped on this specific device, never extracted and decrypted elsewhere.
 *
 * This matters for the same reason as the Mac side: whoever runs the relay server should only
 * ever see ciphertext. The Guardian's browser encrypts the PIN against this public key before it
 * ever leaves their device.
 */
object GuardianKeyManager {
    private const val KEY_ALIAS = "focusguard_guardian_setup_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun publicKeyBase64(): String {
        val entry = loadOrCreateEntry()
        return Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    /** Returns null on any failure (bad base64, decrypt failure, non-UTF8 plaintext). */
    fun decrypt(base64Ciphertext: String): String? {
        return try {
            val entry = loadOrCreateEntry()
            val cipherBytes = Base64.decode(base64Ciphertext, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            val spec = OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT,
            )
            cipher.init(Cipher.DECRYPT_MODE, entry.privateKey, spec)
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadOrCreateEntry(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()

        return keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }
}
