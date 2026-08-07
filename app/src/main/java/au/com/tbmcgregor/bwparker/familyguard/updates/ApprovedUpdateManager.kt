package au.com.tbmcgregor.bwparker.familyguard.updates

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.BuildConfig
import au.com.tbmcgregor.bwparker.familyguard.content.CloudFilterSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class InstallResult {
    data object Started : InstallResult()
    data class Rejected(val reason: String) : InstallResult()
}

/**
 * Fetches, verifies, and installs Otterling updates from the family's own update host -- the
 * *only* update path this app exposes. There is deliberately no UI to install an arbitrary local
 * APK: letting the person being filtered pick their own build defeats every other protection in
 * this app (a self-built APK with the VPN lockdown / proxy fail-closed / CA install / blocklist
 * code quietly removed installs and runs exactly like the real thing). See
 * scripts/update_review_checklist.md and .github/workflows/update-review.yml for the review/sign
 * side of this: only a build that passed AI review against that checklist ever gets signed with
 * the release key and published to the update host.
 *
 * Trust chain enforced entirely on-device, in order:
 * 1. The downloaded manifest names a version/URL/SHA-256.
 * 2. The downloaded APK's own SHA-256 must match the manifest's.
 * 3. The downloaded APK's own signing certificate fingerprint must match
 *    [BuildConfig.RELEASE_CERT_SHA256] (baked in at build time, empty on any build that isn't the
 *    AI-approved CI release build).
 *
 * Step 3 is the actual root of trust, not the manifest alone: a compromised or spoofed update
 * host could publish a resigned APK together with a matching self-authored manifest (steps 1-2
 * would both pass), but it can't forge the release signing key's fingerprint -- only whoever
 * holds that key (never this phone, never the daily dev machine -- see the CI workflow) can
 * produce something step 3 accepts. A mismatch on step 2 or 3 aborts before [installApk] (and
 * therefore `PackageInstaller`) ever sees the file.
 */
class ApprovedUpdateManager(private val context: Context) {
    private val cloudFilterSettings = CloudFilterSettings(context)

    /** Same host as the DNS/proxy filter (see [CloudFilterSettings]) -- one family server, one
     *  place to configure it. */
    fun manifestUrl(): String = "https://${cloudFilterSettings.host()}/updates/manifest.json"

    fun indexUrl(): String = "https://${cloudFilterSettings.host()}/updates/index.json"

    /**
     * Optional multi-component status from the host (android + filter-server + …).
     * Failures return an empty list — the APK path still works via [checkForUpdate].
     */
    suspend fun fetchComponentSummaries(): List<String> = withContext(Dispatchers.IO) {
        try {
            val body = httpGet(indexUrl())
            val root = JSONObject(body)
            val components = root.optJSONArray("components") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until components.length()) {
                    val c = components.getJSONObject(i)
                    val id = c.optString("id", "?")
                    when (c.optString("kind")) {
                        "android_apk" -> add(
                            "Android: ${c.optString("versionName")} (${c.optInt("versionCode")})",
                        )
                        "host_deploy" -> add(
                            "filter-server: ${c.optString("status")} @ ${c.optString("gitSha").take(8)}",
                        )
                        "skip" -> add("$id: ${c.optString("status")}")
                        else -> add("$id: ${c.optString("status", "ok")}")
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "index.json fetch failed", error)
            emptyList()
        }
    }

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val body = httpGet(manifestUrl())
            val obj = JSONObject(body)
            val manifest = UpdateManifest(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.optString("versionName", ""),
                apkUrl = obj.getString("apkUrl"),
                sha256 = obj.getString("sha256").lowercase(),
            )
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                UpdateCheckResult.UpToDate
            } else {
                UpdateCheckResult.UpdateAvailable(manifest)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Update check failed", error)
            UpdateCheckResult.Error(error.message ?: "Update check failed")
        }
    }

    /**
     * Downloads [manifest]'s APK, verifies it (SHA-256 + pinned signing cert, see class doc),
     * then installs it via a self-delegated [PackageInstaller] session -- no "install unknown
     * apps" prompt (see [ensureInstallDelegation]). Aborts before any install attempt if either
     * check fails or if this build has no pinned certificate configured at all. Blocking --
     * call off the main thread (already hopped to [Dispatchers.IO] internally).
     */
    suspend fun downloadVerifyAndInstall(manifest: UpdateManifest): InstallResult = withContext(Dispatchers.IO) {
        val pinnedFingerprint = BuildConfig.RELEASE_CERT_SHA256.lowercase()
        if (pinnedFingerprint.isBlank()) {
            return@withContext InstallResult.Rejected(
                "This build has no pinned release certificate -- refusing to install any update",
            )
        }

        val apkFile = File(context.filesDir, "pending_update.apk")
        try {
            downloadTo(manifest.apkUrl, apkFile)
        } catch (error: Exception) {
            apkFile.delete()
            return@withContext InstallResult.Rejected("Download failed: ${error.message}")
        }

        val actualSha256 = sha256Of(apkFile)
        if (!actualSha256.equals(manifest.sha256, ignoreCase = true)) {
            apkFile.delete()
            return@withContext InstallResult.Rejected("SHA-256 mismatch -- refusing to install")
        }

        val signerFingerprint = signingCertSha256(apkFile)
        if (signerFingerprint == null || !signerFingerprint.equals(pinnedFingerprint, ignoreCase = true)) {
            apkFile.delete()
            return@withContext InstallResult.Rejected(
                "Signing certificate doesn't match the pinned release key -- refusing to install",
            )
        }

        logIfNotDeviceOwner()
        installApk(apkFile)
        InstallResult.Started
    }

    private fun httpGet(url: String): String {
        val connection = openConnectionPreferIpv4(url)
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadTo(url: String, destination: File) {
        val connection = openConnectionPreferIpv4(url)
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.inputStream.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Prefer IPv4 when the update host has a stale AAAA (common for vpn.bartholomew.help).
     * For HTTPS, dial the A record but keep SNI/Host as the real hostname.
     */
    private fun openConnectionPreferIpv4(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val host = url.host ?: throw IllegalArgumentException("URL missing host")
        val ipv4 = runCatching {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
        }.getOrNull()

        if (ipv4 == null || url.protocol != "https") {
            return url.openConnection() as HttpURLConnection
        }

        val ipUrl = URL(url.protocol, ipv4.hostAddress, url.port, url.file)
        val connection = ipUrl.openConnection() as HttpsURLConnection
        connection.setRequestProperty("Host", host)
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        }
        val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        connection.sslSocketFactory = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = defaultFactory.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = defaultFactory.supportedCipherSuites
            override fun createSocket(s: java.net.Socket, hostIgnored: String, port: Int, autoClose: Boolean): java.net.Socket {
                val sock = defaultFactory.createSocket(s, host, port, autoClose) as SSLSocket
                sock.sslParameters = sock.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                }
                return sock
            }
            override fun createSocket(hostIgnored: String, port: Int): java.net.Socket =
                createSocket(InetAddress.getByName(hostIgnored), port)
            override fun createSocket(hostIgnored: String, port: Int, localAddress: InetAddress, localPort: Int): java.net.Socket {
                val sock = defaultFactory.createSocket(hostIgnored, port, localAddress, localPort) as SSLSocket
                sock.sslParameters = sock.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                }
                return sock
            }
            override fun createSocket(address: InetAddress, port: Int): java.net.Socket {
                val sock = defaultFactory.createSocket(address, port) as SSLSocket
                sock.sslParameters = sock.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                }
                return sock
            }
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): java.net.Socket {
                val sock = defaultFactory.createSocket(address, port, localAddress, localPort) as SSLSocket
                sock.sslParameters = sock.sslParameters.apply {
                    serverNames = listOf(SNIHostName(host))
                }
                return sock
            }
        }
        return connection
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signingCertSha256(apkFile: File): String? {
        return try {
            val packageInfo = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: return null
            val signers = packageInfo.signingInfo?.apkContentsSigners ?: return null
            // Exactly one signer expected. Rotated-key lineages (multiple valid signers) would
            // need every accepted key enumerated explicitly -- treat that (or zero signers) as
            // untrusted rather than guessing which one is supposed to matter.
            if (signers.size != 1) return null
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read APK signing certificate", error)
            null
        }
    }

    /**
     * `PackageInstaller.SessionParams#setRequireUserAction(false)` is only honored for a caller
     * that is the device owner, the profile owner, or holds `INSTALL_PACKAGES` -- per Android's
     * own docs, no separate delegation/permission grant is needed beyond already being Device
     * Owner (unlike e.g. SEND_SMS, which does need an explicit `setPermissionGrantState` -- see
     * [au.com.tbmcgregor.bwparker.familyguard.alerts.SmsPermissionGranter]). This just confirms
     * that's actually the case and logs if not, since otherwise [installApk] would silently fall
     * back to showing the normal "install unknown apps" prompt with no console to tap it on.
     */
    private fun logIfNotDeviceOwner() {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(context.packageName) != true) {
            Log.w(TAG, "Not device owner -- silent install may fall back to a user-action prompt")
        }
    }

    private fun installApk(apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("otterling_update", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateInstallResultReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    private companion object {
        const val TAG = "ApprovedUpdateManager"
    }
}
