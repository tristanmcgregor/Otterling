package app.otterling.content

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Looks up which app UID owns a TCP flow, by its local/remote socket tuple, via
 * [ConnectivityManager.getConnectionOwnerUid]. Used by [TcpRelayManager] to decide whether a flow
 * belongs to an app in [MitmExemptManager]'s list -- see that class and [MitmExemptionPolicy] for
 * why this is safe to query from inside a [android.net.VpnService]: the tun is the app's own
 * socket's local address once the default route points at it (no separate NAT rewrite happens),
 * so the tuple this queries is exactly the one the kernel's own connection tracking already
 * associates with the real originating app.
 *
 * API 29+ only (older devices always get `null`, which [MitmExemptionPolicy] treats as "fall back
 * to hostname, or don't exempt" rather than a crash) -- same gating pattern as
 * `PrivateDnsFilterManager.isSupported`.
 */
class AppUidResolver(context: Context) {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    fun ownerUid(localIp: String, localPort: Int, remoteIp: String, remotePort: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cm = connectivityManager ?: return null
        return try {
            val local = InetSocketAddress(InetAddress.getByName(localIp), localPort)
            val remote = InetSocketAddress(InetAddress.getByName(remoteIp), remotePort)
            cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote)
                .takeIf { it != Process.INVALID_UID }
        } catch (error: Exception) {
            Log.w(TAG, "getConnectionOwnerUid($localIp:$localPort -> $remoteIp:$remotePort) failed", error)
            null
        }
    }

    private companion object {
        const val TAG = "AppUidResolver"
    }
}
