package app.otterling.monitoring

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.content.CloudFilterSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** classification is one of "safe", "nsfw", "skipped" (visualFilterEnabled off server-side), or
 * "error" (classifier unavailable -- treat exactly like "safe", never block on this). */
data class ScreenshotClassifyResult(val classification: String, val blockUntilMillis: Long?)

/**
 * Uploads a captured foreground-app screenshot to filter-server's `/screenshot-classify` (see
 * `lockprofile_service.py` and `nsfw_image_classifier.py`) for server-side NSFW classification --
 * see FocusGuardAccessibilityService.kt's capture path for why this is server-side, not on-device.
 *
 * Same `LOCKPROFILE_TOKEN`/host config as [DeviceLogUploader] -- one shared device-bearer
 * credential for every low-stakes phone->server call, not a separate one per feature.
 */
object ScreenshotUploader {
    suspend fun upload(
        context: Context,
        packageName: String,
        imageBytes: ByteArray,
    ): Result<ScreenshotClassifyResult> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = MacTamperPollSettings(context)
            check(settings.isConfigured()) { "Server token not configured (see Mac tamper alerts settings)" }

            val host = CloudFilterSettings(context).host()
            check(host.isNotBlank()) { "Filter server host not configured" }

            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown-device"
            val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val requestBody = JSONObject()
                .put("device_id", deviceId)
                .put("package_name", packageName)
                .put("image_base64", imageBase64)
                .toString()

            val connection = URL("https://$host/screenshot-classify").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                // Server-side classification can take up to CLAUDE_TIMEOUT_SECONDS=45s (vision
                // model round trip) -- longer than DeviceLogUploader's plain-upload timeout.
                connection.readTimeout = 60_000
                connection.setRequestProperty("Authorization", "Bearer ${settings.token()}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                check(code in 200..299) { "Upload failed: HTTP $code" }
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseBody)
                ScreenshotClassifyResult(
                    classification = json.optString("classification", "error"),
                    blockUntilMillis = if (json.isNull("blockUntilMillis")) null else json.optLong("blockUntilMillis"),
                )
            } finally {
                connection.disconnect()
            }
        }.onFailure { error ->
            Log.w(TAG, "Screenshot upload failed", error)
        }
    }

    private const val TAG = "ScreenshotUploader"
}
