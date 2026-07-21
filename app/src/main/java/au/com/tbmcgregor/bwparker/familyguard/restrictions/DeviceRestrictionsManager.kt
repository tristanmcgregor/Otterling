package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger

class DeviceRestrictionsManager(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    fun isEnabled(restriction: Restriction): Boolean =
        devicePolicyManager?.getUserRestrictions(adminComponent)
            ?.getBoolean(restriction.userManagerKey) == true

    fun setEnabled(restriction: Restriction, enabled: Boolean) {
        val dpm = devicePolicyManager ?: return
        try {
            if (enabled) {
                dpm.addUserRestriction(adminComponent, restriction.userManagerKey)
            } else {
                dpm.clearUserRestriction(adminComponent, restriction.userManagerKey)
            }
            Log.i(TAG, "${restriction.name} set to $enabled")
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to change ${restriction.name} (device owner not active yet?)", error)
        }
    }

    fun isUninstallBlocked(): Boolean =
        devicePolicyManager?.isUninstallBlocked(adminComponent, context.packageName) == true

    fun setUninstallBlocked(blocked: Boolean) {
        try {
            devicePolicyManager?.setUninstallBlocked(adminComponent, context.packageName, blocked)
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to set uninstall-blocked (device owner not active yet?)", error)
        }
    }

    /** Called once from [DeviceAdminReceiverImpl.onEnabled] so protection is on by default. */
    fun applyDefaults() {
        Restriction.entries.forEach { setEnabled(it, true) }
        setUninstallBlocked(true)
        Log.i(TAG, "Applied default tamper-resistance restrictions")
    }

    suspend fun detectDriftAndReapply(logger: TamperEventLogger) {
        val missing = Restriction.entries.filterNot(::isEnabled).map { it.displayName }.toMutableList()
        if (!isUninstallBlocked()) missing += "Block app uninstall"
        if (missing.isEmpty()) return

        logger.log(
            type = "RESTRICTION_DRIFT",
            details = "Protection disabled or missing: ${missing.joinToString()}",
        )
        applyDefaults()
    }

    private companion object {
        const val TAG = "DeviceRestrictionsManager"
    }
}
