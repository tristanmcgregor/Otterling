package app.otterling.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import app.otterling.R
import app.otterling.admin.DeviceAdminReceiverImpl
import java.security.cert.CertificateFactory

/**
 * Installs the filter proxy's CA certificate as a device-wide trusted root via Device Owner's
 * `DevicePolicyManager.installCaCert`, so the mitmproxy filter server's on-the-fly-generated leaf
 * certificates (one per intercepted host) are trusted without per-app/browser exceptions. Required
 * for [TcpRelayManager]'s CONNECT-based HTTPS filtering to actually work -- without this, every
 * HTTPS site behind the proxy fails TLS validation instead of loading.
 *
 * NOTE (corrected 2026-08-27): `res/raw/otterling_proxy_ca.pem` is the REAL CA certificate for
 * this deployment (`CN=mitmproxy`, issued 2026-08-05) -- NOT a placeholder, which is what this
 * comment used to claim. It is the public certificate only (no private key), which is exactly what
 * has to ship inside the APK for the phone to validate mitmproxy's leaf certificates. See
 * filter-server/ca/README.md for verification steps and the full correction.
 *
 * The old wording ("a placeholder generated locally, not by a real mitmproxy instance... must be
 * replaced before the proxy filter can work") was actively misleading in both directions: someone
 * following it would have replaced a working CA believing it was inert, and an auditor would have
 * skipped the file believing it carried nothing real. If you deploy a different mitmproxy, its CA
 * will differ and this file does need replacing -- but for THIS deployment it is already correct.
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
