package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * Best-effort helpers for apps that refuse [DevicePolicyManager.setPackagesSuspended] because they
 * are an active device admin.
 *
 * Android will not let a Device Owner strip another production app's device admin, so callers
 * should fall back to [PackageDisableStore] when this returns false.
 */
object ActiveAdminRemover {
    private const val TAG = "ActiveAdminRemover"

    fun activeAdminsForPackage(context: Context, packageName: String): List<ComponentName> {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return emptyList()
        return dpm.getActiveAdmins()
            ?.filter { it.packageName == packageName }
            .orEmpty()
    }

    fun forceRemoveAdminsForPackage(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) {
            Log.w(TAG, "Refusing to remove our own device-owner admin")
            return false
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not device owner -- cannot attempt admin removal")
            return false
        }
        val targets = activeAdminsForPackage(context, packageName)
        if (targets.isEmpty()) return true
        for (admin in targets) {
            tryRemoveAdmin(dpm, admin)
        }
        return activeAdminsForPackage(context, packageName).isEmpty()
    }

    fun suspendEvenIfAdmin(context: Context, packageName: String): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val ourAdmin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        if (activeAdminsForPackage(context, packageName).isNotEmpty()) {
            val stripped = forceRemoveAdminsForPackage(context, packageName)
            if (!stripped) {
                Log.w(TAG, "Android refused to remove device admin for $packageName")
            }
        }
        return try {
            dpm.setPackagesSuspended(ourAdmin, arrayOf(packageName), true).isEmpty()
        } catch (error: SecurityException) {
            Log.e(TAG, "suspendEvenIfAdmin failed for $packageName", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "suspendEvenIfAdmin failed for $packageName", error)
            false
        }
    }

    private fun tryRemoveAdmin(dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            dpm.removeActiveAdmin(admin)
            if (!dpm.isAdminActive(admin)) {
                Log.i(TAG, "removeActiveAdmin succeeded for $admin")
                return
            }
        } catch (error: SecurityException) {
            Log.d(TAG, "removeActiveAdmin refused for $admin: ${error.message}")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val userId = Class.forName("android.os.UserHandle")
                .getMethod("myUserId")
                .invoke(null) as Int
            DevicePolicyManager::class.java
                .getMethod("forceRemoveActiveAdmin", ComponentName::class.java, Int::class.javaPrimitiveType)
                .invoke(dpm, admin, userId)
            Log.i(TAG, "forceRemoveActiveAdmin invoked for $admin")
        } catch (error: ReflectiveOperationException) {
            Log.d(TAG, "forceRemoveActiveAdmin unavailable/refused for $admin: ${error.cause ?: error}")
        } catch (error: RuntimeException) {
            Log.d(TAG, "forceRemoveActiveAdmin failed for $admin: $error")
        }
    }
}
