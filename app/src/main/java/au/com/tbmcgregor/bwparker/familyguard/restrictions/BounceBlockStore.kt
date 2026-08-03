package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.content.Context

/**
 * Packages that should be kicked out of the foreground when opened, used when
 * [DevicePolicyManager.setPackagesSuspended] refuses (typically because the package is an active
 * device admin). Keeps the package enabled so its own accessibility / monitoring can keep running.
 */
class BounceBlockStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBlocked(packageName: String): Boolean =
        prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().contains(packageName)

    fun setBlocked(packageName: String, blocked: Boolean) {
        val current = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        if (blocked) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_PACKAGES).apply()
    }

    private companion object {
        const val PREFS_NAME = "bounce_block_store"
        const val KEY_PACKAGES = "packages"
    }
}
