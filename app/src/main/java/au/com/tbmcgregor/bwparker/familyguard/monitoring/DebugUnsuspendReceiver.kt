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
import au.com.tbmcgregor.bwparker.familyguard.restrictions.ActiveAdminRemover
import au.com.tbmcgregor.bwparker.familyguard.restrictions.BounceBlockStore

/**
 * DEBUG-ONLY receiver: clears live blocks OR tries to strip another app's device admin and
 * suspend it (usually fails for production admins -- falls back to bounce-block).
 *
 * Clear all suspensions:
 *   adb shell am broadcast -a au.com.tbmcgregor.bwparker.familyguard.DEBUG_UNSUSPEND \
 *     -n au.com.tbmcgregor.bwparker.familyguard/.monitoring.DebugUnsuspendReceiver
 *
 * Strip admin + suspend / bounce (e.g. Accountable2You):
 *   adb shell am broadcast -a au.com.tbmcgregor.bwparker.familyguard.DEBUG_STRIP_ADMIN \
 *     -n au.com.tbmcgregor.bwparker.familyguard/.monitoring.DebugUnsuspendReceiver \
 *     --esa packages com.accountable2you.ap1.googleplay
 */
class DebugUnsuspendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val action = intent?.action.orEmpty()
        val packages = intent?.getStringArrayExtra(EXTRA_PACKAGES)
        val pending = goAsync()
        Thread {
            try {
                when {
                    action.endsWith("DEBUG_STRIP_ADMIN") -> {
                        val bounce = BounceBlockStore(context)
                        for (pkg in packages?.toList().orEmpty()) {
                            val ok = ActiveAdminRemover.suspendEvenIfAdmin(context, pkg)
                            if (!ok) bounce.setBlocked(pkg, blocked = true)
                            else bounce.setBlocked(pkg, blocked = false)
                            Log.i(TAG, "strip+suspend $pkg -> suspendOk=$ok bounce=${!ok}")
                        }
                    }
                    packages.isNullOrEmpty() -> clearAll(context)
                    else -> {
                        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return@Thread
                        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
                        val failed = dpm.setPackagesSuspended(admin, packages, false)
                        Log.i(TAG, "Unsuspended ${packages.toList()}; failed=${failed.toList()}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "DebugUnsuspend failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun clearAll(context: Context) {
        BounceBlockStore(context).clearAll()
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
