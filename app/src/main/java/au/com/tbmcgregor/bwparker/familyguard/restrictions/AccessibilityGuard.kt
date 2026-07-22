package au.com.tbmcgregor.bwparker.familyguard.restrictions

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.focus.FocusGuardAccessibilityService

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
 *   The allowlist is computed from whatever's currently enabled (so it never fights a legitimate
 *   accessibility-based app you've already turned on, e.g. Accountable2You) plus this app itself,
 *   so only genuinely *new* accessibility services are blocked from being enabled going forward.
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

    /** Re-derives the permitted-accessibility-services allowlist from whatever's enabled right
     * now, unioned with this app's own package, and locks this app in as the only allowed
     * lock-task package (needed for [au.com.tbmcgregor.bwparker.familyguard.tamper.AccessibilityGuardActivity]'s
     * pinned nag screen). Safe to call repeatedly/periodically. */
    fun reapplyAllowlist(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, DeviceAdminReceiverImpl::class.java)
        val currentlyEnabledPackages = enabledComponents(context)
            ?.mapNotNull { it.substringBefore('/', missingDelimiterValue = "").takeIf(String::isNotBlank) }
            ?: emptyList()
        val allowlist = (currentlyEnabledPackages + context.packageName).distinct()
        try {
            dpm.setPermittedAccessibilityServices(admin, allowlist)
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to lock down accessibility services (device owner not active yet?)", error)
        }
    }
}
