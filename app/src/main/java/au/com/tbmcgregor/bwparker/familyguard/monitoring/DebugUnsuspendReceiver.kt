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
import au.com.tbmcgregor.bwparker.familyguard.restrictions.AccessibilityGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.ActiveAdminRemover
import au.com.tbmcgregor.bwparker.familyguard.restrictions.CompanionAppGuard
import au.com.tbmcgregor.bwparker.familyguard.restrictions.PackageDisableStore

/**
 * DEBUG-ONLY receiver: clears live blocks OR tries to strip another app's device admin and
 * disable it when suspend fails.
 *
 * Clear all suspensions:
 *   adb shell am broadcast -a au.com.tbmcgregor.bwparker.familyguard.DEBUG_UNSUSPEND \
 *     -n au.com.tbmcgregor.bwparker.familyguard/.monitoring.DebugUnsuspendReceiver
 *
 * Strip admin + suspend / hide (e.g. Accountable2You):
 *   adb shell am broadcast -a au.com.tbmcgregor.bwparker.familyguard.DEBUG_STRIP_ADMIN \
 *     -n au.com.tbmcgregor.bwparker.familyguard/.monitoring.DebugUnsuspendReceiver \
 *     --esa packages com.accountable2you.ap1.googleplay
 *
 * Force hide/disable (skip suspend):
 *   adb shell am broadcast -a au.com.tbmcgregor.bwparker.familyguard.DEBUG_DISABLE \
 *     -n au.com.tbmcgregor.bwparker.familyguard/.monitoring.DebugUnsuspendReceiver \
 *     --esa packages com.accountable2you.ap1.googleplay
 *
 * Re-permit companion accessibility (Accountable2You etc.):
 *   adb shell am broadcast -a au.com.tbmcgregor.bwparker.familyguard.DEBUG_PERMIT_A11Y \
 *     -n au.com.tbmcgregor.bwparker.familyguard/.monitoring.DebugUnsuspendReceiver
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
                    action.endsWith("DEBUG_PERMIT_A11Y") -> {
                        AccessibilityGuard.ALWAYS_PERMITTED_PACKAGES.forEach {
                            AccessibilityGuard.permitPackage(context, it)
                        }
                        AccessibilityGuard.reapplyAllowlist(context)
                        Log.i(TAG, "Reapplied accessibility allowlist including companions")
                    }
                    action.endsWith("DEBUG_PROTECT_COMPANIONS") -> {
                        kotlinx.coroutines.runBlocking {
                            CompanionAppGuard.reapplyAll(context)
                        }
                        Log.i(TAG, "Companion protections reapplied")
                    }
                    action.endsWith("DEBUG_DISABLE") -> {
                        val disableStore = PackageDisableStore(context)
                        for (pkg in packages?.toList().orEmpty()) {
                            val ok = disableStore.disable(pkg)
                            Log.i(TAG, "force-disable $pkg -> $ok")
                        }
                    }
                    action.endsWith("DEBUG_STRIP_ADMIN") -> {
                        val disableStore = PackageDisableStore(context)
                        for (pkg in packages?.toList().orEmpty()) {
                            val ok = ActiveAdminRemover.suspendEvenIfAdmin(context, pkg)
                            if (ok) {
                                disableStore.markBlocked(pkg)
                                Log.i(TAG, "strip+suspend $pkg -> suspendOk=true")
                            } else {
                                val disabled = disableStore.disable(pkg)
                                Log.i(TAG, "strip+suspend $pkg -> suspendOk=false disableOk=$disabled")
                            }
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
        PackageDisableStore(context).clearAll()
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
