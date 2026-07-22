package au.com.tbmcgregor.bwparker.familyguard.pin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Guardian setup relay (`server/guardian_relay.py`) to claim this phone's half of a
 * one-time setup link and apply it as the app's PIN. Uses plain `HttpURLConnection` rather than
 * pulling in a networking library -- this is one short-lived call, not worth a new dependency.
 */
object GuardianSetupClient {
    sealed class ClaimResult {
        object Success : ClaimResult()
        data class Failure(val message: String) : ClaimResult()
    }

    suspend fun claimPin(serverBaseUrl: String, token: String, pinAuthManager: PinAuthManager): ClaimResult =
        withContext(Dispatchers.IO) {
            try {
                val base = serverBaseUrl.trimEnd('/')
                val url = URL("$base/drop/$token/phone")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    return@withContext ClaimResult.Failure(
                        if (status == 404) {
                            "Nothing to claim yet -- has the Guardian submitted the link?"
                        } else {
                            "Server error ($status): $body"
                        }
                    )
                }

                val body = connection.inputStream.bufferedReader().readText()
                val ciphertext = JSONObject(body).optString("ciphertext", "")
                if (ciphertext.isEmpty()) {
                    return@withContext ClaimResult.Failure("Malformed response from relay server.")
                }

                val pin = GuardianKeyManager.decrypt(ciphertext)
                    ?: return@withContext ClaimResult.Failure("Could not decrypt payload.")
                if (pin.isBlank()) {
                    return@withContext ClaimResult.Failure("Decrypted PIN was empty.")
                }

                pinAuthManager.setPin(pin)
                ClaimResult.Success
            } catch (e: Exception) {
                ClaimResult.Failure("Failed to reach relay server: ${e.message}")
            }
        }
}
