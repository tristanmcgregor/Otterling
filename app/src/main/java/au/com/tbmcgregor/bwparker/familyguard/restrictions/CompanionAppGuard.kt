package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import au.com.tbmcgregor.bwparker.familyguard.data.ProtectedApp

/**
 * Companion apps that Otterling permanently shields: uninstall blocked, user-control
 * locked (no force-stop / clear-data from Settings on API 30+), always permitted for accessibility,
 * and never suspended/hidden by habit rules or manual blocks.
 */
object CompanionAppGuard {
    private const val TAG = "CompanionAppGuard"

    const val ACCOUNTABLE2YOU = "com.accountable2you.ap1.googleplay"

    val PACKAGES: List<String> = listOf(ACCOUNTABLE2YOU)

    fun isCompanion(packageName: String): Boolean = packageName in PACKAGES

    /**
     * Re-asserts every companion protection. Safe to call periodically from the enforcement loop.
     */
    suspend fun reapplyAll(context: Context) {
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(appContext, DeviceAdminReceiverImpl::class.java)
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) {
            Log.w(TAG, "Not device owner -- cannot protect companions")
            return
        }

        val pm = appContext.packageManager
        val installed = PACKAGES.filter { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
        }
        if (installed.isEmpty()) {
            Log.i(TAG, "No companion packages installed")
            applyUserControlLock(dpm, admin, appContext.packageName, emptyList())
            AccessibilityGuard.reapplyAllowlist(appContext)
            return
        }

        val uninstallDao = AppDatabase.getInstance(appContext).protectedAppDao()
        val disableStore = PackageDisableStore(appContext)

        for (pkg in installed) {
            // Persist + apply uninstall block.
            uninstallDao.upsert(ProtectedApp(pkg))
            runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
                .onFailure { Log.e(TAG, "setUninstallBlocked failed for $pkg", it) }

            // Never leave a companion suspended/hidden/disabled.
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
            runCatching {
                val state = pm.getApplicationEnabledSetting(pkg)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                ) {
                    pm.setApplicationEnabledSetting(
                        pkg,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        0,
                    )
                }
            }.onFailure { Log.w(TAG, "Could not re-enable $pkg", it) }

            // Exempt from habit/manual block reapply.
            disableStore.undisable(pkg)
            AccessibilityGuard.permitPackage(appContext, pkg)
            Log.i(TAG, "Protected companion $pkg")
        }

        applyUserControlLock(dpm, admin, appContext.packageName, installed)
        AccessibilityGuard.reapplyAllowlist(appContext)
    }

    /** Clears companion-specific user-control locks (called when master protection turns off). */
    fun clearUserControlLocks(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setUserControlDisabledPackages(admin, emptyList()) }
                .onFailure { Log.w(TAG, "clearUserControlLocks failed", it) }
        }
    }

    private fun applyUserControlLock(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        selfPackage: String,
        companions: List<String>,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val locked = (listOf(selfPackage) + companions).distinct()
        runCatching {
            dpm.setUserControlDisabledPackages(admin, locked)
            Log.i(TAG, "User-control disabled packages: $locked")
        }.onFailure { Log.e(TAG, "setUserControlDisabledPackages failed", it) }
    }
}
