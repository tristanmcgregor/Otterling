package app.otterling.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import app.otterling.admin.DeviceAdminReceiverImpl
import app.otterling.focus.FocusGuardAccessibilityService

/**
 * Stock Android has no Device Owner API that can literally stop the user flipping our own
 * accessibility service's switch off in Settings > Accessibility: [DevicePolicyManager]'s
 * `setSecureSetting` isn't allowed to touch `ENABLED_ACCESSIBILITY_SERVICES` (it's not on the
 * device-owner secure-settings allowlist), and [DevicePolicyManager.setPermittedAccessibilityServices]
 * only gates which accessibility services can be turned *on*, not whether an already-enabled one
 * can be turned back *off*. So this is detect-and-respond, not true prevention:
 *
 * - [isEnabled] is polled/observed elsewhere (see `ProtectionEnforcementService` and
 *   `RestrictionEnforcementWorker`), and when it's found off, a full-screen, back-press-swallowing
 *   nag pinned via Lock Task Mode (which a device owner can enter without the user confirming a
 *   dialog) redirects straight to Accessibility settings and won't let go until it's back on --
 *   see `AccessibilityGuardActivity`.
 * - [reapplyAllowlist] closes the one real gap that would otherwise remain: without it, the user
 *   could enable a *different* accessibility service as a workaround for whatever ours enforces.
 *   The allowlist always includes this app, currently-enabled accessibility packages, and any
 *   packages previously remembered via [permitPackage].
 */
object AccessibilityGuard {
    private const val TAG = "AccessibilityGuard"

    fun ourComponentName(context: Context): String =
        ComponentName(context, FocusGuardAccessibilityService::class.java).flattenToString()

    fun isEnabled(context: Context): Boolean {
        val expected = ourComponentName(context)
        val enabled = enabledComponents(context) ?: return false
        return enabled.any { it.equals(expected, ignoreCase = true) }
    }

    private fun enabledComponents(context: Context): List<String>? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.filter { it.isNotBlank() }

    /** Settings must be reachable from inside the pinned nag screen -- its own "Open Accessibility
     * settings" button is how you're meant to get out, by turning the service back on -- so it
     * has to be in the lock-task allowlist too, or [Context.startActivity] for it silently does
     * nothing while [app.otterling.tamper.AccessibilityGuardActivity] is
     * pinned, leaving no way out of the nag screen except us fixing it over adb. */
    private const val SETTINGS_PACKAGE = "com.android.settings"

    /**
     * Re-derives the permitted-accessibility-services allowlist from currently enabled packages,
     * this app, and any packages previously remembered via [permitPackage]. Safe to call
     * repeatedly/periodically.
     */
    fun reapplyAllowlist(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remembered = prefs.getStringSet(KEY_PERMITTED, emptySet()).orEmpty()
            .filterNot { it in LEGACY_REMOVED_PACKAGES }
        val currentlyEnabledPackages = enabledComponents(context)
            ?.mapNotNull { it.substringBefore('/', missingDelimiterValue = "").takeIf(String::isNotBlank) }
            ?.filterNot { it in LEGACY_REMOVED_PACKAGES }
            ?: emptyList()
        val allowlist = (
            currentlyEnabledPackages +
                context.packageName +
                remembered
            ).distinct()
        prefs.edit().putStringSet(KEY_PERMITTED, allowlist.toSet()).apply()
        try {
            dpm.setPermittedAccessibilityServices(admin, allowlist)
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName, SETTINGS_PACKAGE))
            Log.i(TAG, "Permitted accessibility packages: $allowlist")
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to lock down accessibility services (device owner not active yet?)", error)
        }
    }

    /** Adds [packageName] to the remembered permit list and reapplies immediately. */
    fun permitPackage(context: Context, packageName: String) {
        if (packageName in LEGACY_REMOVED_PACKAGES) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY_PERMITTED, emptySet()).orEmpty().toMutableSet()
        next.add(packageName)
        prefs.edit().putStringSet(KEY_PERMITTED, next).apply()
        reapplyAllowlist(context)
    }

    private const val PREFS_NAME = "accessibility_guard"
    private const val KEY_PERMITTED = "permitted_packages"

    /** Former companion packages no longer shielded by Otterling. */
    private val LEGACY_REMOVED_PACKAGES = setOf(
        "com.accountable2you.ap1.googleplay",
        "com.accountable2you.reportsapp",
    )
}
