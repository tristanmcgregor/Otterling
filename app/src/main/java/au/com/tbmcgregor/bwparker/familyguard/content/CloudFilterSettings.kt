package au.com.tbmcgregor.bwparker.familyguard.content

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * Encrypted settings for the cloud-hosted content filter (see filter-server/) deployed to a host
 * the family controls: DNS (fallback/failsafe layer, [DomainBlocklistManager] is the client-side
 * always-on one) plus a real HTTPS MITM proxy (the primary filtering path -- [TcpRelayManager]
 * CONNECTs every captured TCP 80/443 flow through it instead of relaying directly, and the proxy
 * server decides whether to block the whole request/page, not just scrub in-page content). Host
 * and proxy credentials are treated as sensitive since they identify and can be used to reach the
 * family's own filter deployment; port/enabled flags aren't secrets so stay in plain prefs,
 * matching [GuardianAlertSettings]'s split.
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

    /** Sub-toggle under [isEnabled]: whether TCP 80/443 gets CONNECT-proxied through the mitmproxy
     *  filter (the primary, page-content-aware filtering path) as well as DNS. Defaults on, since
     *  DNS-only filtering alone lets any HTTPS site load in full regardless of page content. */
    fun isProxyEnabled(): Boolean = isEnabled() && prefs.getBoolean(KEY_PROXY_ENABLED, true)

    fun setProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply()
    }

    fun proxyPort(): Int = prefs.getInt(KEY_PROXY_PORT, DEFAULT_PROXY_PORT)

    fun setProxyPort(port: Int) {
        prefs.edit().putInt(KEY_PROXY_PORT, port).apply()
    }

    fun proxyUser(): String = securePrefs.getString(KEY_PROXY_USER, DEFAULT_PROXY_USER).orEmpty().ifBlank { DEFAULT_PROXY_USER }

    fun setProxyUser(user: String) {
        securePrefs.edit().putString(KEY_PROXY_USER, user.trim()).apply()
    }

    fun proxyPassword(): String = securePrefs.getString(KEY_PROXY_PASSWORD, "").orEmpty()

    fun setProxyPassword(password: String) {
        securePrefs.edit().putString(KEY_PROXY_PASSWORD, password).apply()
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

    /**
     * Connects to the configured proxy host:proxyPort (not protected, same reasoning as
     * [testReachable]: this runs from the UI process, which the tunnel excludes) and attempts a
     * real HTTP CONNECT for a throwaway destination, reporting whether the proxy replied with a
     * successful (2xx) status within [timeoutMs]. A stronger signal than a bare TCP connect test,
     * since it also validates the configured credentials and that this is genuinely speaking the
     * CONNECT proxy protocol, not just that something is listening on the port. Blocking -- call
     * off the main thread.
     */
    fun testProxyReachable(timeoutMs: Int = 5_000): Boolean {
        val targetHost = host()
        if (targetHost.isEmpty()) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(targetHost, proxyPort()), timeoutMs)
                socket.soTimeout = timeoutMs
                val credentials = Base64.getEncoder().encodeToString("${proxyUser()}:${proxyPassword()}".toByteArray())
                val request = "CONNECT example.com:443 HTTP/1.1\r\n" +
                    "Host: example.com:443\r\n" +
                    "Proxy-Authorization: Basic $credentials\r\n" +
                    "\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val buffer = ByteArray(512)
                val read = socket.getInputStream().read(buffer)
                if (read <= 0) return false
                val statusLine = String(buffer, 0, read, Charsets.US_ASCII).lineSequence().firstOrNull().orEmpty()
                Regex("""HTTP/1\.[01] 2\d\d""").containsMatchIn(statusLine)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Proxy reachability probe failed", error)
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
        const val KEY_PROXY_ENABLED = "proxy_enabled"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_PROXY_USER = "proxy_user"
        const val KEY_PROXY_PASSWORD = "proxy_password"
        const val DEFAULT_HOST = "vpn.bartholomew.help"
        const val DEFAULT_PORT = 53
        const val DEFAULT_PROXY_PORT = 8080
        const val DEFAULT_PROXY_USER = "otterling"
    }
}
