package au.com.tbmcgregor.bwparker.familyguard.content

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Encrypted settings for the cloud-hosted DNS content filter (a Canopy-style upstream filter --
 * see filter-server/ -- deployed to a host the family controls). Host is treated as sensitive
 * since it identifies and can be used to reach the family's own filter deployment; port/enabled
 * aren't secrets so stay in plain prefs, matching [GuardianAlertSettings]'s split.
 */
@Suppress("DEPRECATION")
class CloudFilterSettings(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val securePrefs = EncryptedSharedPreferences.create(
        appContext,
        SECURE_PREFS,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Only actually "on" once a host is configured -- an enabled toggle with no host would just
     *  mean every DNS query silently falls through to the last-resort upstream. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false) && host().isNotEmpty()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun host(): String = securePrefs.getString(KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }

    fun setHost(host: String) {
        securePrefs.edit().putString(KEY_HOST, host.trim()).apply()
    }

    fun port(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        prefs.edit().putInt(KEY_PORT, port).apply()
    }

    /**
     * Sends a throwaway DNS query straight to the configured host:port (NOT through
     * [protect] -- this runs from the UI process, which the VPN tunnel excludes by default, so it
     * doesn't need protecting) and reports whether any reply came back within [timeoutMs].
     * Blocking -- call off the main thread.
     */
    fun testReachable(timeoutMs: Int = 3_000): Boolean {
        val targetHost = host()
        if (targetHost.isEmpty()) return false
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val query = DnsMessage.buildQuery("example.com")
                val target = InetSocketAddress(InetAddress.getByName(targetHost), port())
                socket.send(DatagramPacket(query, query.size, target))
                val responseBuffer = ByteArray(512)
                socket.receive(DatagramPacket(responseBuffer, responseBuffer.size))
                true
            }
        } catch (error: Exception) {
            Log.w(TAG, "Cloud filter reachability probe failed", error)
            false
        }
    }

    private companion object {
        const val TAG = "CloudFilterSettings"
        const val SECURE_PREFS = "cloud_filter_secure"
        const val PREFS = "cloud_filter_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val DEFAULT_HOST = "vpn.bartholomew.help"
        const val DEFAULT_PORT = 53
    }
}
