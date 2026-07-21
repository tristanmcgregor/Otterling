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

    private val preferences = RestrictionPreferences(context)

    fun isEnabled(restriction: Restriction): Boolean =
        devicePolicyManager?.getUserRestrictions(adminComponent)
            ?.getBoolean(restriction.userManagerKey) == true

    /** Applies the change and remembers it as the parent's intended state for drift checks. */
    fun setEnabled(restriction: Restriction, enabled: Boolean) {
        preferences.setDesired(restriction, enabled)
        applyToSystem(restriction, enabled)
    }

    private fun applyToSystem(restriction: Restriction, enabled: Boolean) {
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

    /** Applies the change and remembers it as the parent's intended state for drift checks. */
    fun setUninstallBlocked(blocked: Boolean) {
        preferences.setUninstallBlockDesired(blocked)
        applyUninstallBlockedToSystem(blocked)
    }

    private fun applyUninstallBlockedToSystem(blocked: Boolean) {
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

    /**
     * Reapplies whatever the parent last chose (via [setEnabled]/[setUninstallBlocked]), not a
     * hardcoded "always on" default. Only a live restriction that disagrees with that chosen
     * state is treated as drift/tampering and logged -- so intentionally disabling something
     * (e.g. USB debugging, temporarily, to install an update over ADB) doesn't get silently
     * reverted a few minutes later.
     */
    suspend fun detectDriftAndReapply(logger: TamperEventLogger) {
        val drifted = mutableListOf<String>()

        Restriction.entries.forEach { restriction ->
            val desired = preferences.isDesired(restriction)
            if (isEnabled(restriction) != desired) {
                drifted += restriction.displayName
                applyToSystem(restriction, desired)
            }
        }

        val desiredUninstallBlocked = preferences.isUninstallBlockDesired()
        if (isUninstallBlocked() != desiredUninstallBlocked) {
            drifted += "Block app uninstall"
            applyUninstallBlockedToSystem(desiredUninstallBlocked)
        }

        if (drifted.isEmpty()) return
        logger.log(
            type = "RESTRICTION_DRIFT",
            details = "Protection changed unexpectedly, restored: ${drifted.joinToString()}",
        )
    }

    private companion object {
        const val TAG = "DeviceRestrictionsManager"
    }
}
