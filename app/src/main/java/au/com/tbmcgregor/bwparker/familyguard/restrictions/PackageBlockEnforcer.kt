package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * Shared suspend → strip-admin → hide/disable-user fallback used by habit rules, manual blocks,
 * budgets, and reward apps.
 */
object PackageBlockEnforcer {
    private const val TAG = "PackageBlockEnforcer"

    fun setBlocked(context: Context, packageName: String, blocked: Boolean) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        val disableStore = PackageDisableStore(context)

        if (!blocked) {
            disableStore.release(packageName)
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(packageName), false) }
            return
        }

        if (disableStore.isExempt(packageName)) {
            Log.i(TAG, "Skip block for $packageName -- user undisabled (exempt)")
            return
        }

        val suspended = try {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), true).isEmpty()
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to suspend $packageName", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Cannot suspend $packageName", error)
            false
        }

        if (suspended) {
            // Suspended successfully — drop any prior hide/disable tracking.
            disableStore.release(packageName)
            return
        }

        Log.w(TAG, "Suspend refused for $packageName -- trying admin strip then hide/disable")
        if (ActiveAdminRemover.suspendEvenIfAdmin(context, packageName)) {
            disableStore.release(packageName)
            return
        }

        if (!disableStore.disable(packageName)) {
            Log.e(
                TAG,
                "Could not hide/disable $packageName. Device Owner can't disable-user without " +
                    "privileged permission; hide also fails while the package is an active device admin. " +
                    "One-time: adb shell pm disable-user --user 0 $packageName && adb shell pm enable $packageName",
            )
        }
    }
}
