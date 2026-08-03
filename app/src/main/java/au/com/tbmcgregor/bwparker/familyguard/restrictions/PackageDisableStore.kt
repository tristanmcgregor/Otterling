package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl

/**
 * Fallback when [DevicePolicyManager.setPackagesSuspended] refuses (e.g. the target is/was an
 * active device admin).
 *
 * Prefer [DevicePolicyManager.setApplicationHidden] (Device Owner can do this). Plain
 * [PackageManager.setApplicationEnabledSetting] requires `CHANGE_COMPONENT_ENABLED_STATE`, which
 * a normal Device Owner app does **not** have — so disable-user from inside the app fails even
 * though `adb shell pm disable-user` works.
 *
 * [undisable] unhides/re-enables and marks the package exempt so habit-rule reapply won't
 * immediately re-block until [disable] is called again.
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

    fun isCurrentlyDisabled(packageName: String): Boolean {
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
                disabled = isCurrentlyDisabled(pkg),
                exempt = isExempt(pkg),
            )
        }.sortedBy { it.label.lowercase() }

    /**
     * Hides (preferred) or disable-user (best-effort) [packageName]. Returns true if the package
     * ends up hidden/disabled.
     */
    fun disable(packageName: String): Boolean {
        if (packageName == appContext.packageName) return false
        mutateSet(KEY_EXEMPT) { it.remove(packageName) }
        mutateSet(KEY_TRACKED) { it.add(packageName) }

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
            isCurrentlyDisabled(packageName)
        } catch (error: SecurityException) {
            Log.w(TAG, "disable-user refused for $packageName (needs privileged permission)", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "disable-user failed for $packageName", error)
            false
        }
        Log.i(TAG, "disable $packageName -> $disabled")
        return disabled
    }

    /**
     * Unhides/re-enables [packageName] and exempts it from automatic re-disable until [disable]
     * is called again (or [clearAll]).
     */
    fun undisable(packageName: String): Boolean {
        mutateSet(KEY_TRACKED) { it.add(packageName) }
        mutateSet(KEY_EXEMPT) { it.add(packageName) }
        return enablePackage(packageName)
    }

    /** Unhides/re-enables and drops tracking/exemption — used when a rule unlocks. */
    fun release(packageName: String) {
        if (packageName in trackedPackages() || isCurrentlyDisabled(packageName)) {
            enablePackage(packageName)
        }
        mutateSet(KEY_TRACKED) { it.remove(packageName) }
        mutateSet(KEY_EXEMPT) { it.remove(packageName) }
    }

    fun clearAll() {
        trackedPackages().forEach { enablePackage(it) }
        prefs.edit().remove(KEY_TRACKED).remove(KEY_EXEMPT).apply()
    }

    private fun enablePackage(packageName: String): Boolean {
        val unhidden = hidePackage(packageName, hidden = false)
        val enabled = try {
            if (isEnabledSettingDisabled(packageName)) {
                pm.setApplicationEnabledSetting(
                    packageName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    0,
                )
            }
            !isCurrentlyDisabled(packageName)
        } catch (error: SecurityException) {
            Log.w(TAG, "enable refused for $packageName", error)
            unhidden && !isHidden(packageName)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "enable failed for $packageName", error)
            false
        }
        Log.i(TAG, "enable $packageName -> $enabled (unhidden=$unhidden)")
        return enabled
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
        val disabled: Boolean,
        val exempt: Boolean,
    )

    private companion object {
        const val TAG = "PackageDisableStore"
        const val PREFS_NAME = "package_disable_store"
        const val KEY_TRACKED = "tracked"
        const val KEY_EXEMPT = "exempt"
    }
}
