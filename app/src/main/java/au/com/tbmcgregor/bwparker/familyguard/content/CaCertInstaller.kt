package au.com.tbmcgregor.bwparker.familyguard.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.R
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import java.security.cert.CertificateFactory

/**
 * Installs the filter proxy's CA certificate as a device-wide trusted root via Device Owner's
 * `DevicePolicyManager.installCaCert`, so the mitmproxy filter server's on-the-fly-generated leaf
 * certificates (one per intercepted host) are trusted without per-app/browser exceptions. Required
 * for [TcpRelayManager]'s CONNECT-based HTTPS filtering to actually work -- without this, every
 * HTTPS site behind the proxy fails TLS validation instead of loading.
 *
 * The bundled `res/raw/otterling_proxy_ca.pem` is a placeholder generated locally (not by a real
 * mitmproxy instance) purely so this compiles out of the box -- see filter-server/ca/README.md.
 * It must be replaced with the real CA extracted from your own filter-server deployment before the
 * proxy filter can work; until then, [installIfNeeded] happily installs the wrong (harmless,
 * useless) certificate.
 */
class CaCertInstaller(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    private fun caCertDer(): ByteArray? {
        return try {
            context.resources.openRawResource(R.raw.otterling_proxy_ca).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input).encoded
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load bundled proxy CA cert", error)
            null
        }
    }

    /** True if the bundled CA is already present in this admin's installed CA cert list. */
    fun isInstalled(): Boolean {
        val dpm = devicePolicyManager ?: return false
        val der = caCertDer() ?: return false
        return try {
            dpm.getInstalledCaCerts(adminComponent).any { it.contentEquals(der) }
        } catch (error: SecurityException) {
            false
        }
    }

    /**
     * Installs the bundled CA as a device-wide trusted root if it isn't already. Idempotent and
     * safe to call every time the VPN is enabled/re-asserted -- re-adding an already-installed
     * cert is a no-op, and this checks first anyway to avoid the DPM call entirely in the common
     * case. Returns false if Device Owner isn't active or the bundled cert can't be read.
     */
    fun installIfNeeded(): Boolean {
        val dpm = devicePolicyManager ?: return false
        val der = caCertDer() ?: return false
        if (isInstalled()) return true
        return try {
            dpm.installCaCert(adminComponent, der)
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to install CA cert (device owner not active?)", error)
            false
        }
    }

    private companion object {
        const val TAG = "CaCertInstaller"
    }
}
