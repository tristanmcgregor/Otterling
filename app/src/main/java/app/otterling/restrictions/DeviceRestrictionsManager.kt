package app.otterling.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import app.otterling.admin.DeviceAdminReceiverImpl
import app.otterling.tamper.TamperEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeviceRestrictionsManager(private val context: Context) {
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    private val preferences = RestrictionPreferences(context)

    // Fire-and-forget only -- setEnabled/setUninstallBlocked stay synchronous (many call sites,
    // including Settings-screen SwitchRow onClick handlers with no coroutine scope of their own)
    // while still being able to invoke TamperEventLogger.log's suspend API.
    private val alertScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isEnabled(restriction: Restriction): Boolean =
        devicePolicyManager?.getUserRestrictions(adminComponent)
            ?.getBoolean(restriction.userManagerKey) == true

    /**
     * Applies the change and remembers it as the parent's intended state for drift checks.
     * Deliberately alerts on the front-door disable itself, not just later drift -- turning a
     * protection off through this app's own Settings screen is exactly the kind of "trying to get
     * around it" action that should reach an accountability partner, not just external tampering.
     */
    fun setEnabled(restriction: Restriction, enabled: Boolean) {
        val wasEnabled = isEnabled(restriction)
        preferences.setDesired(restriction, enabled)
        applyToSystem(restriction, enabled)
        if (!enabled && wasEnabled) {
            alertScope.launch {
                runCatching {
                    TamperEventLogger(context).log(
                        type = "RESTRICTION_DISABLED_BY_USER",
                        details = "${restriction.displayName} turned off via Settings",
                        debounceKey = "RESTRICTION_DISABLED_BY_USER|${restriction.name}",
                    )
                }
            }
        }
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

    /** Applies the change and remembers it as the parent's intended state for drift checks.
     *  Alerts on the front-door disable itself, same reasoning as [setEnabled]. */
    fun setUninstallBlocked(blocked: Boolean) {
        val wasBlocked = isUninstallBlocked()
        preferences.setUninstallBlockDesired(blocked)
        applyUninstallBlockedToSystem(blocked)
        if (!blocked && wasBlocked) {
            alertScope.launch {
                runCatching {
                    TamperEventLogger(context).log(
                        type = "UNINSTALL_PROTECTION_DISABLED_BY_USER",
                        details = "Uninstall protection turned off via Settings",
                    )
                }
            }
        }
    }

    private fun applyUninstallBlockedToSystem(blocked: Boolean) {
        try {
            devicePolicyManager?.setUninstallBlocked(adminComponent, context.packageName, blocked)
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to set uninstall-blocked (device owner not active yet?)", error)
        }
    }

    /** Clears every tamper restriction from the live system without changing stored preferences. */
    fun clearAllFromSystem() {
        Restriction.entries.forEach { applyToSystem(it, enabled = false) }
        applyUninstallBlockedToSystem(blocked = false)
    }

    /** Re-applies whatever is stored in preferences (used when protection is turned back on). */
    fun reapplyDesiredFromPreferences() {
        Restriction.entries.forEach { applyToSystem(it, preferences.isDesired(it)) }
        applyUninstallBlockedToSystem(preferences.isUninstallBlockDesired())
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
