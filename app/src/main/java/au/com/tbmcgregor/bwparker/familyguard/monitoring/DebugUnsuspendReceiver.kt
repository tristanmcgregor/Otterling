package au.com.tbmcgregor.bwparker.familyguard.monitoring

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * DEBUG-ONLY receiver: clears live blocks (DPM suspensions + user-disabled packages).
 *
 *   adb shell am broadcast -a au.com.tbmcgregor.bwparker.familyguard.DEBUG_UNSUSPEND \
 *     -n au.com.tbmcgregor.bwparker.familyguard/.monitoring.DebugUnsuspendReceiver
 *
 * Optional: --esa packages pkg1,pkg2 to only unsuspend those packages.
 */
class DebugUnsuspendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val packages = intent?.getStringArrayExtra(EXTRA_PACKAGES)
        val pending = goAsync()
        Thread {
            try {
                if (packages.isNullOrEmpty()) {
                    clearAll(context)
                } else {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return@Thread
                    val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
                    val failed = dpm.setPackagesSuspended(admin, packages, false)
                    Log.i(TAG, "Unsuspended ${packages.toList()}; failed=${failed.toList()}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unsuspend failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun clearAll(context: Context) {
        val pm = context.packageManager
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        val self = context.packageName
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrElse { pm.getInstalledApplications(0) }
        val suspended = mutableListOf<String>()
        for (app in apps) {
            val pkg = app.packageName
            if (pkg == self) continue
            if (runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(false)) suspended += pkg
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue
            val state = runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull() ?: continue
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                runCatching {
                    pm.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
                    Log.i(TAG, "Re-enabled $pkg")
                }
            }
        }
        if (suspended.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false)
            Log.i(TAG, "Cleared ${suspended.size} suspensions")
        }
    }

    companion object {
        const val EXTRA_PACKAGES = "packages"
        private const val TAG = "DebugUnsuspend"
    }
}
