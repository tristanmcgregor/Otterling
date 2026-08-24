package app.otterling.monitoring

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import app.otterling.admin.DeviceAdminReceiverImpl
import app.otterling.tamper.DeviceRemovedActivity

/**
 * Handles the server telling this phone it was removed from the guardian's dashboard (see
 * [app.otterling.content.DashboardConfigStore.refresh]'s `removed` check on the authenticated
 * settings poll -- NOT the FCM push payload itself, which is deliberately untrusted, see
 * [app.otterling.alerts.MacTamperMessagingService]'s doc comment). The dashboard's "Remove device"
 * button is guardian-session-only -- confirmed via `Caddyfile`'s device-bearer allowlist, which
 * does not include the device-removal DELETE route -- so reaching this code path means an
 * authenticated guardian genuinely decided to decommission this specific device; it is not
 * something this device, or whoever is holding it, can trigger on its own.
 *
 * Mirrors `MainActivity`'s `DEBUG-ONLY applyDebugClearDeviceOwner()` flow, just reachable in
 * production from a real server signal instead of an ADB extra. Same ordering for the same
 * reason: [ProtectionController.shutdown] first (it depends on Device Owner/VPN still being
 * active to fully unwind them), only then clear the always-on VPN lock, the uninstall block, and
 * finally Device Owner status itself -- clearing Device Owner earlier would leave some of
 * `shutdown()`'s own DPM calls unable to run.
 *
 * Idempotent by construction: every step here (`shutdown()`, `setUninstallBlocked(false)`,
 * `clearDeviceOwnerApp`) already no-ops or fails harmlessly if already applied, and
 * [DeviceRemovedActivity] doesn't need duplicate-launch guarding the way
 * `AccessibilityGuardActivity` does, since re-showing it (e.g. on a later poll, before the user
 * has actually uninstalled) is harmless. This matters because [handle] will keep being called
 * again on every ~15-minute poll (or faster, via the FCM wake the server also sends) for as long
 * as the app remains installed after removal.
 */
object DeviceRemovalHandler {
    private const val TAG = "DeviceRemovalHandler"

    suspend fun handle(context: Context) {
        val appContext = context.applicationContext
        Log.w(TAG, "Device removed by guardian -- disabling all protections and offering to uninstall")

        runCatching { ProtectionEnforcementService.stop(appContext) }
        runCatching { ProtectionController(appContext).shutdown() }
            .onFailure { Log.w(TAG, "ProtectionController.shutdown() failed", it) }

        val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(appContext, DeviceAdminReceiverImpl::class.java)
        if (dpm != null) {
            // Unconditional, not gated on shutdown()'s own "was VPN on" tracking -- this must clear
            // even if that flag was somehow already stale, since a lingering always-on lock with no
            // app left to satisfy it would break networking entirely. Matches the debug path's own
            // defensive stance.
            runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
            runCatching { dpm.setUninstallBlocked(admin, appContext.packageName, false) }
            runCatching { dpm.clearDeviceOwnerApp(appContext.packageName) }
                .onSuccess { Log.i(TAG, "Device Owner status cleared") }
                .onFailure { Log.w(TAG, "clearDeviceOwnerApp failed", it) }
        }

        DeviceRemovedActivity.launch(appContext)
    }
}
