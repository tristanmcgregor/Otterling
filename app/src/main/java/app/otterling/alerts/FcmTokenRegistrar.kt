package app.otterling.alerts

import android.content.Context
import android.os.Build
import android.util.Log
import app.otterling.content.CloudFilterSettings
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Hands this device's FCM registration token to the filter-server so it can push a "poll now" wake
 * on a Mac tamper event (see [MacTamperMessagingService]). Registration is best-effort and
 * idempotent: it's called on every app launch ([registerCurrentToken]) and on token rotation
 * ([MacTamperMessagingService.onNewToken]), and skips the network entirely when the token hasn't
 * changed since the last successful POST -- so a missed registration is retried next launch, and
 * push simply stays on the 15-minute-poll fallback until it lands.
 *
 * Same server + credential as the poll: host from [CloudFilterSettings], Bearer
 * [MacTamperPollSettings.token] (`LOCKPROFILE_TOKEN`). Endpoint: `POST /alerts/register-token`.
 */
object FcmTokenRegistrar {
    private const val TAG = "FcmTokenRegistrar"

    /** Ask FCM for the current token and register it. Safe to call on every launch. */
    fun registerCurrentToken(context: Context) {
        val appContext = context.applicationContext
        if (!MacTamperPollSettings(appContext).isConfigured()) return // No server/token yet -- nothing to register with.
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> register(appContext, token) }
            .addOnFailureListener { error -> Log.w(TAG, "Could not obtain FCM token", error) }
    }

    /** POST [token] to the server unless it matches the last one we successfully registered. */
    fun register(context: Context, token: String) {
        val appContext = context.applicationContext
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured() || token.isBlank()) return
        if (token == settings.lastRegisteredFcmToken()) return // Already registered this exact token.

        val host = CloudFilterSettings(appContext).host()
        thread(name = "fcm-register") {
            try {
                val body = JSONObject()
                    .put("token", token)
                    .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .toString()
                val connection = (URL("https://$host/alerts/register-token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${settings.token()}")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Connection", "close")
                }
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                connection.inputStream.use { it.readBytes() }
                if (code == 200) {
                    settings.setLastRegisteredFcmToken(token)
                    Log.i(TAG, "Registered FCM token with filter-server")
                } else {
                    Log.w(TAG, "Token registration returned HTTP $code")
                }
            } catch (error: Exception) {
                // Best-effort: next launch / token rotation retries. Push just falls back to polling.
                Log.w(TAG, "Token registration failed", error)
            }
        }
    }
}
