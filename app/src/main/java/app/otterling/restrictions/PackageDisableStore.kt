package app.otterling.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.otterling.admin.DeviceAdminReceiverImpl

/**
 * Tracks apps blocked via suspend / hide when a habit or manual block targets them, and backs the
 * Settings "Undisable" button.
 *
 * Prefer suspend; fall back to [DevicePolicyManager.setApplicationHidden] when suspend is refused
 * (e.g. active device admin). Plain disable-user usually fails for a Device Owner app (needs
 * `CHANGE_COMPONENT_ENABLED_STATE`).
 *
 * [undisable] unsuspends + unhides and marks the package exempt so reapply won't immediately
 * re-block until [disable] / [markBlocked] runs again.
 */
class PackageDisableStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pm = appContext.packageManager
    private val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(appContext, DeviceAdminReceiverImpl::class.java)

    fun trackedPackages(): Set<String> =
        prefs.getStringSet(KEY_TRACKED, emptySet()).orEmpty()

    fun isExempt(packageName: String): Boolean =
        prefs.getStringSet(KEY_EXEMPT, emptySet()).orEmpty().contains(packageName)

    /** True if the package is suspended, hidden, or disabled-user. */
    fun isCurrentlyBlocked(packageName: String): Boolean {
        if (isSuspended(packageName)) return true
        if (isHidden(packageName)) return true
        val state = runCatching { pm.getApplicationEnabledSetting(packageName) }.getOrNull()
            ?: return false
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    fun visibleEntries(): List<Entry> =
        trackedPackages().map { pkg ->
            Entry(
                packageName = pkg,
                label = labelFor(pkg),
                blocked = isCurrentlyBlocked(pkg),
                exempt = isExempt(pkg),
            )
        }.sortedBy { it.label.lowercase() }

    /** Remember [packageName] as managed/blocked (clears exemption). Does not change system state. */
    fun markBlocked(packageName: String) {
        if (packageName == appContext.packageName) return
        mutateSet(KEY_EXEMPT) { it.remove(packageName) }
        mutateSet(KEY_TRACKED) { it.add(packageName) }
    }

    /**
     * Hides (preferred) or disable-user (best-effort) [packageName]. Returns true if the package
     * ends up blocked.
     */
    fun disable(packageName: String): Boolean {
        if (packageName == appContext.packageName) return false
        markBlocked(packageName)

        // Prefer suspend when possible.
        if (suspendPackage(packageName, suspended = true)) {
            Log.i(TAG, "suspend $packageName -> true")
            return true
        }

        val hidden = hidePackage(packageName, hidden = true)
        if (hidden) {
            Log.i(TAG, "hide $packageName -> true")
            return true
        }

        val disabled = try {
            pm.setApplicationEnabledSetting(
                packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0,
            )
            isCurrentlyBlocked(packageName)
        } catch (error: SecurityException) {
            Log.w(TAG, "disable-user refused for $packageName", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "disable-user failed for $packageName", error)
            false
        }
        Log.i(TAG, "disable $packageName -> $disabled")
        return disabled
    }

    /**
     * Unsuspends/unhides/re-enables [packageName] and exempts it from automatic re-block until
     * [disable] / [markBlocked] is called again.
     */
    fun undisable(packageName: String): Boolean {
        mutateSet(KEY_TRACKED) { it.add(packageName) }
        mutateSet(KEY_EXEMPT) { it.add(packageName) }
        suspendPackage(packageName, suspended = false)
        return enablePackage(packageName)
    }

    /** Unsuspends/unhides and drops tracking/exemption — used when a rule unlocks. */
    fun release(packageName: String) {
        if (packageName in trackedPackages() || isCurrentlyBlocked(packageName)) {
            suspendPackage(packageName, suspended = false)
            enablePackage(packageName)
        }
        mutateSet(KEY_TRACKED) { it.remove(packageName) }
        mutateSet(KEY_EXEMPT) { it.remove(packageName) }
    }

    fun clearAll() {
        trackedPackages().forEach { pkg ->
            suspendPackage(pkg, suspended = false)
            enablePackage(pkg)
        }
        prefs.edit().remove(KEY_TRACKED).remove(KEY_EXEMPT).apply()
    }

    private fun enablePackage(packageName: String): Boolean {
        hidePackage(packageName, hidden = false)
        try {
            if (isEnabledSettingDisabled(packageName)) {
                pm.setApplicationEnabledSetting(
                    packageName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    0,
                )
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "enable refused for $packageName", error)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "enable failed for $packageName", error)
        }
        val ok = !isHidden(packageName) && !isEnabledSettingDisabled(packageName)
        Log.i(TAG, "enable $packageName -> $ok")
        return ok
    }

    private fun suspendPackage(packageName: String, suspended: Boolean): Boolean {
        val policy = dpm ?: return false
        return try {
            policy.setPackagesSuspended(admin, arrayOf(packageName), suspended).isEmpty()
        } catch (error: SecurityException) {
            Log.w(TAG, "setPackagesSuspended($suspended) refused for $packageName", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "setPackagesSuspended($suspended) failed for $packageName", error)
            false
        }
    }

    private fun hidePackage(packageName: String, hidden: Boolean): Boolean {
        val policy = dpm ?: return false
        return try {
            val ok = policy.setApplicationHidden(admin, packageName, hidden)
            ok && isHidden(packageName) == hidden
        } catch (error: SecurityException) {
            Log.w(TAG, "setApplicationHidden($hidden) refused for $packageName", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "setApplicationHidden($hidden) failed for $packageName", error)
            false
        }
    }

    private fun isSuspended(packageName: String): Boolean =
        runCatching { pm.isPackageSuspended(packageName) }.getOrDefault(false)

    private fun isHidden(packageName: String): Boolean {
        val policy = dpm ?: return false
        return runCatching { policy.isApplicationHidden(admin, packageName) }.getOrDefault(false)
    }

    private fun isEnabledSettingDisabled(packageName: String): Boolean {
        val state = runCatching { pm.getApplicationEnabledSetting(packageName) }.getOrNull()
            ?: return false
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    private fun labelFor(packageName: String): String = runCatching {
        val info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun mutateSet(key: String, mutate: (MutableSet<String>) -> Unit) {
        val next = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        mutate(next)
        prefs.edit().putStringSet(key, next).apply()
    }

    data class Entry(
        val packageName: String,
        val label: String,
        val blocked: Boolean,
        val exempt: Boolean,
    )

    private companion object {
        const val TAG = "PackageDisableStore"
        const val PREFS_NAME = "package_disable_store"
        const val KEY_TRACKED = "tracked"
        const val KEY_EXEMPT = "exempt"
    }
}
