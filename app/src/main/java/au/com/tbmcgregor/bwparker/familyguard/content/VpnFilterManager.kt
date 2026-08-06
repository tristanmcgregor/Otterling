package au.com.tbmcgregor.bwparker.familyguard.content

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.monitoring.ProtectionController
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * Registers [VpnFilterService] as the device's mandatory always-on VPN via Device Owner's
 * [DevicePolicyManager.setAlwaysOnVpnPackage], with `lockdownEnabled = true`. The always-on VPN
 * setting is locked out of the user-facing Settings UI either way. Requires Device Owner -- no
 * consent dialog is shown, unlike a normal app calling `VpnService.prepare()`.
 *
 * Lockdown is safe now because [VpnFilterService] captures a full default route
 * (`addRoute("0.0.0.0", 0)`) and relays every non-DNS connection back out itself via
 * [TcpRelayManager]/[UdpRelayManager] -- every destination has an explicit path through the
 * tunnel, so lockdown's "no path at all without an explicit route" behavior doesn't strand
 * anything. This also means another VPN app cannot get a path around the filter: with lockdown
 * on, Android refuses all network access to anything other than this VPN, so a second VPN's own
 * tunnel simply never gets network access to establish over.
 *
 * Also reconciles with [PrivateDnsFilterManager]: device-wide strict/forced-host Private DNS gets
 * validated on *every* active network, including this filter VPN's own network the moment it
 * comes up -- but that network only ever speaks plain DNS (port 53), never DNS-over-TLS, so that
 * validation always fails and surfaces to the user as a "Private DNS server can't be accessed"
 * system notification. Since this VPN's own blocklist already does the same content-filtering job,
 * [enable] falls Private DNS back to opportunistic (remembering the previous host) rather than
 * have the two features fight over the same job, and [disable] restores it.
 */
class VpnFilterManager(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val privateDnsFilterManager = PrivateDnsFilterManager(context)

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

    /**
     * Starts the filter service and locks it in as the mandatory always-on VPN, with lockdown
     * enabled -- no other VPN and no cleartext bypass gets network access while this is set.
     * Blocking call (the Private DNS reconciliation may perform a connectivity check) -- call off
     * the main thread.
     */
    fun enable(): Boolean {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        val dpm = devicePolicyManager ?: return false
        // Register the always-on VPN FIRST: this is what actually makes the system bring the
        // tunnel back up on its own after a reboot (it binds the service itself, which isn't
        // subject to the background foreground-service-start throttling that a BOOT_COMPLETED
        // receiver is). The manual start below is only a best-effort fast-path for the "just
        // toggled it on while the app is open" case -- if it throws (e.g. started from the boot
        // receiver on Android 12+), we must NOT let that abort the always-on registration, or the
        // VPN silently never comes back after a restart.
        val registered = try {
            dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, true)
            suppressConflictingPrivateDns()
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to set always-on VPN (device owner not active?)", error)
            false
        } catch (error: UnsupportedOperationException) {
            Log.e(TAG, "Device doesn't support always-on VPN lockdown", error)
            false
        }
        runCatching { VpnFilterService.start(context) }
            .onFailure { Log.w(TAG, "Direct VPN start failed (always-on will bring it up)", it) }
        return registered
    }

    /**
     * Removes the always-on lock and stops the service -- requires Settings/PIN access. Blocking
     * call (the Private DNS restore may perform a connectivity check) -- call off the main thread.
     */
    fun disable(): Boolean {
        val dpm = devicePolicyManager
        val cleared = if (dpm == null) {
            false
        } else {
            try {
                // lockdownEnabled is moot once vpnPackage is null (nothing to lock down to), but
                // the platform still requires a value -- false here so a partially-failed clear
                // never leaves the device in a lockdown state with no VPN package registered.
                dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
                true
            } catch (error: SecurityException) {
                Log.e(TAG, "Not authorized to clear always-on VPN", error)
                false
            }
        }
        VpnFilterService.stop(context)
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        restoreSuppressedPrivateDns()
        return cleared
    }

    private fun suppressConflictingPrivateDns() {
        val currentHost = privateDnsFilterManager.currentHost() ?: return
        prefs.edit().putString(KEY_SAVED_PRIVATE_DNS_HOST, currentHost).apply()
        privateDnsFilterManager.disable()
    }

    private fun restoreSuppressedPrivateDns() {
        val savedHost = prefs.getString(KEY_SAVED_PRIVATE_DNS_HOST, null) ?: return
        prefs.edit().remove(KEY_SAVED_PRIVATE_DNS_HOST).apply()
        // Restore whatever host the user actually had -- not just one that happens to match one
        // of our own two FilterProfile entries. This used to silently discard any custom Private
        // DNS host the user had configured themselves (anything other than our two presets),
        // leaving Private DNS stuck on opportunistic forever after the VPN was ever toggled on.
        privateDnsFilterManager.enableHost(savedHost)
    }

    /** Call on boot -- re-locks the always-on VPN if it was previously turned on. */
    fun reapplyIfEnabled() {
        if (!ProtectionController(context).isEnabled()) return
        if (wasEnabledByUser()) enable()
    }

    /**
     * Cheap, idempotent watchdog meant to be called periodically (e.g. every 60s, on
     * [Dispatchers.IO]). Re-asserts the always-on registration if it has drifted away from us and
     * makes sure the service is up, so the filter VPN effectively never stays disconnected. No-op
     * when the user has the VPN intentionally off. Never throws -- all DPM/system calls are guarded.
     */
    fun ensureActive() {
        if (!ProtectionController(context).isEnabled()) return
        if (!wasEnabledByUser()) return
        val dpm = devicePolicyManager ?: return
        runCatching {
            val current = dpm.getAlwaysOnVpnPackage(adminComponent)
            if (current != context.packageName || !isLockdownEnabled()) {
                Log.w(TAG, "Always-on VPN drifted (was $current, lockdown=${isLockdownEnabled()}) -- re-registering")
                dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, true)
                suppressConflictingPrivateDns()
            }
        }.onFailure { Log.w(TAG, "Watchdog always-on re-registration failed", it) }
        // Best-effort: starting an already-running foreground service is a harmless no-op and does
        // NOT rebuild the tunnel (the service's own running flag is already set), so this only
        // matters when the service somehow isn't up.
        runCatching { VpnFilterService.start(context) }
            .onFailure { Log.w(TAG, "Watchdog service start failed", it) }
    }

    private companion object {
        const val TAG = "VpnFilterManager"
        const val PREFS_NAME = "vpn_filter_manager_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_SAVED_PRIVATE_DNS_HOST = "saved_private_dns_host"
    }
}
