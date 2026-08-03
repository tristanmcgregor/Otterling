package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Fallback when [android.app.admin.DevicePolicyManager.setPackagesSuspended] refuses (e.g. the
 * target is an active device admin). Disables the package via
 * [PackageManager.setApplicationEnabledSetting] and remembers which packages we touched so Settings
 * can show an Undisable button.
 *
 * [undisable] re-enables the package and marks it exempt so habit-rule reapply won't immediately
 * disable it again until [disable] is called (or the rule unlocks / protection turns off).
 */
class PackageDisableStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pm = appContext.packageManager

    fun trackedPackages(): Set<String> =
        prefs.getStringSet(KEY_TRACKED, emptySet()).orEmpty()

    fun isExempt(packageName: String): Boolean =
        prefs.getStringSet(KEY_EXEMPT, emptySet()).orEmpty().contains(packageName)

    fun isCurrentlyDisabled(packageName: String): Boolean {
        val state = runCatching { pm.getApplicationEnabledSetting(packageName) }.getOrNull()
            ?: return false
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    /** Packages we manage that are either currently disabled or user-exempt (for the Settings UI). */
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
     * Disables [packageName] and clears any exemption. Returns true if the package ends up disabled
     * (or already was).
     */
    fun disable(packageName: String): Boolean {
        if (packageName == appContext.packageName) return false
        mutateSet(KEY_EXEMPT) { it.remove(packageName) }
        mutateSet(KEY_TRACKED) { it.add(packageName) }
        return try {
            pm.setApplicationEnabledSetting(
                packageName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                0,
            )
            val ok = isCurrentlyDisabled(packageName)
            Log.i(TAG, "disable $packageName -> $ok")
            ok
        } catch (error: SecurityException) {
            Log.e(TAG, "disable refused for $packageName", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "disable failed for $packageName", error)
            false
        }
    }

    /**
     * Re-enables [packageName] and exempts it from automatic re-disable until [disable] is called
     * again (or [clearAll]).
     */
    fun undisable(packageName: String): Boolean {
        mutateSet(KEY_TRACKED) { it.add(packageName) }
        mutateSet(KEY_EXEMPT) { it.add(packageName) }
        return enablePackage(packageName)
    }

    /** Re-enables and drops tracking/exemption — used when a rule unlocks or protection turns off. */
    fun release(packageName: String) {
        enablePackage(packageName)
        mutateSet(KEY_TRACKED) { it.remove(packageName) }
        mutateSet(KEY_EXEMPT) { it.remove(packageName) }
    }

    fun clearAll() {
        trackedPackages().forEach { enablePackage(it) }
        prefs.edit().remove(KEY_TRACKED).remove(KEY_EXEMPT).apply()
    }

    private fun enablePackage(packageName: String): Boolean = try {
        pm.setApplicationEnabledSetting(
            packageName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            0,
        )
        val ok = !isCurrentlyDisabled(packageName)
        Log.i(TAG, "enable $packageName -> $ok")
        ok
    } catch (error: SecurityException) {
        Log.e(TAG, "enable refused for $packageName", error)
        false
    } catch (error: IllegalArgumentException) {
        Log.e(TAG, "enable failed for $packageName", error)
        false
    }

    private fun labelFor(packageName: String): String = runCatching {
        val info = pm.getApplicationInfo(packageName, 0)
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
