package app.otterling.alerts

import android.content.Context
import android.os.Build
import android.util.Log
import app.otterling.content.CloudFilterSettings
import app.otterling.content.DashboardConfigStore
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
        // FirebaseMessaging.getInstance() throws IllegalStateException synchronously when no
        // google-services.json was present at build time (see app/build.gradle.kts's
        // googleServicesJson comment) -- there's no default FirebaseApp to back it. Both call
        // sites (MainActivity, DeviceAdminReceiverImpl) call this unguarded, so without this catch
        // a build shipped without google-services.json crashes app launch and device-admin
        // onEnabled instead of just falling back to MacTamperPollWorker's 15-minute poll as
        // documented above.
        val messaging = try {
            FirebaseMessaging.getInstance()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Firebase not configured (no google-services.json) -- instant tamper alerts disabled, falling back to the 15-minute poll", error)
            return
        }
        messaging.token
            .addOnSuccessListener { token -> register(appContext, token) }
            .addOnFailureListener { error -> Log.w(TAG, "Could not obtain FCM token", error) }
    }

    /** POST [token] to the server unless it (and this device's own id) already match the last
     *  successful registration. The device_id check is what lets an already-registered install
     *  (from before per-device FCM targeting existed) re-POST exactly once to backfill that
     *  association, even though its token itself hasn't changed. */
    fun register(context: Context, token: String) {
        val appContext = context.applicationContext
        val settings = MacTamperPollSettings(appContext)
        if (!settings.isConfigured() || token.isBlank()) return
        val deviceId = DashboardConfigStore(appContext).deviceId()
        if (token == settings.lastRegisteredFcmToken() && deviceId == settings.lastRegisteredFcmDeviceId()) {
            return // Already registered this exact token+device_id pair.
        }

        val host = CloudFilterSettings(appContext).host()
        thread(name = "fcm-register") {
            try {
                val body = JSONObject()
                    .put("token", token)
                    .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("device_id", deviceId)
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
                    settings.setLastRegisteredFcmDeviceId(deviceId)
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
