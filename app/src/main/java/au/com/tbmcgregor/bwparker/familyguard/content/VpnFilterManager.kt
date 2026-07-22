package au.com.tbmcgregor.bwparker.familyguard.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * Registers [VpnFilterService] as the device's mandatory always-on VPN via Device Owner's
 * [DevicePolicyManager.setAlwaysOnVpnPackage]. The always-on VPN setting is locked out of the
 * user-facing Settings UI either way. Requires Device Owner -- no consent dialog is shown, unlike
 * a normal app calling `VpnService.prepare()`.
 *
 * Deliberately does NOT pass `lockdownEnabled = true`: lockdown requires every single connection
 * to have an explicit route through the tunnel, and this VPN only captures DNS + a handful of
 * known DoH/DoT resolver IPs (see [VpnFilterService]) rather than a full default route, since
 * routing everything would require reimplementing a general TCP/IP NAT relay. Under lockdown,
 * anything without an explicit route gets no path at all instead of falling back to the normal
 * network -- which is what caused the "no internet" incident this comment is here to explain.
 */
class VpnFilterManager(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persisted separately from the DPM query so [reapplyIfEnabled] survives a DPM/system reset. */
    fun wasEnabledByUser(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun isLockdownEnabled(): Boolean {
        val dpm = devicePolicyManager ?: return false
        return try {
            dpm.isAlwaysOnVpnLockdownEnabled(adminComponent)
        } catch (error: SecurityException) {
            false
        }
    }

    /** Starts the filter service and locks it in as the mandatory always-on VPN (no lockdown). */
    fun enable(): Boolean {
        VpnFilterService.start(context)
        val dpm = devicePolicyManager ?: return false
        return try {
            dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, false)
            prefs.edit().putBoolean(KEY_ENABLED, true).apply()
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to set always-on VPN (device owner not active?)", error)
            false
        } catch (error: UnsupportedOperationException) {
            Log.e(TAG, "Device doesn't support always-on VPN lockdown", error)
            false
        }
    }

    /** Removes the always-on lock and stops the service -- requires Settings/PIN access. */
    fun disable(): Boolean {
        val dpm = devicePolicyManager
        val cleared = if (dpm == null) {
            false
        } else {
            try {
                dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
                true
            } catch (error: SecurityException) {
                Log.e(TAG, "Not authorized to clear always-on VPN", error)
                false
            }
        }
        VpnFilterService.stop(context)
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        return cleared
    }

    /** Call on boot -- re-locks the always-on VPN if it was previously turned on. */
    fun reapplyIfEnabled() {
        if (wasEnabledByUser()) enable()
    }

    private companion object {
        const val TAG = "VpnFilterManager"
        const val PREFS_NAME = "vpn_filter_manager_prefs"
        const val KEY_ENABLED = "enabled"
    }
}
