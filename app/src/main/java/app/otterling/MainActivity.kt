package app.otterling

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.otterling.admin.DeviceOwnerManager
import app.otterling.alerts.FcmTokenRegistrar
import app.otterling.alerts.MacTamperPollWorker
import app.otterling.content.BlocklistRefreshWorker
import app.otterling.monitoring.ProtectionController
import app.otterling.monitoring.ProtectionEnforcementService
import app.otterling.onboarding.OnboardingState
import app.otterling.onboarding.OnboardingStep
import app.otterling.onboarding.resolveOnboardingStep
import app.otterling.pin.PinAuthManager
import app.otterling.updates.UpdateCheckWorker
import app.otterling.restrictions.BatteryOptimizationManager
import app.otterling.restrictions.RestrictionEnforcementWorker
import app.otterling.ui.DashboardScreen
import app.otterling.ui.OnboardingWizard
import app.otterling.ui.PinLockScreen
import app.otterling.ui.nav.AppShell
import app.otterling.ui.theme.FamilyGuardTheme

class MainActivity : ComponentActivity() {
    private enum class Screen { Home, Onboarding, PinEntry, Settings }

    /**
     * DEBUG-ONLY hook: lets the PIN-gated Settings screen be opened directly via ADB, because
     * the PIN lives in EncryptedSharedPreferences and can't be set from the shell.
     *
     * Strictly gated on the app being debuggable so release/Play builds can never bypass the PIN.
     * Does NOT alter the normal Home -> PinEntry -> Settings flow; it's purely an extra entry point.
     *
     * Trigger it with:
     *   adb shell am start -n app.otterling/.MainActivity --ez open_settings true
     */
    private val isDebuggable
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun wantsDebugSettings(intent: Intent?) =
        isDebuggable && intent?.getBooleanExtra("open_settings", false) == true

    /**
     * DEBUG-ONLY: unsuspend packages that Device Owner previously suspended (pm unsuspend can't
     * clear a DPM suspension). Usage:
     *   adb shell am start -n …/.MainActivity --esa unsuspend_packages pkg1,pkg2
     */
    private fun applyDebugUnsuspend(intent: Intent?) {
        if (!isDebuggable) return
        val packages = intent?.getStringArrayExtra("unsuspend_packages") ?: return
        if (packages.isEmpty()) return
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return
        val admin = android.content.ComponentName(
            this,
            app.otterling.admin.DeviceAdminReceiverImpl::class.java,
        )
        runCatching {
            dpm.setPackagesSuspended(admin, packages, false)
        }
    }

    /**
     * DEBUG-ONLY: clear Otterling uninstall-block / hide for packages (and default A2Y leftovers).
     *   adb shell am start -n …/.MainActivity --ez clear_a2y_policies true -f 0x10008000
     */
    private fun applyDebugClearA2yPolicies(intent: Intent?) {
        if (!isDebuggable) return
        if (intent?.getBooleanExtra("clear_a2y_policies", false) != true) return
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return
        val admin = android.content.ComponentName(
            this,
            app.otterling.admin.DeviceAdminReceiverImpl::class.java,
        )
        val targets = listOf(
            "com.accountable2you.ap1.googleplay",
            "com.accountable2you.reportsapp",
        )
        // Delete DB rows first so EnforcementService reapply can't immediately re-block.
        kotlinx.coroutines.runBlocking {
            val dao = app.otterling.data.AppDatabase
                .getInstance(applicationContext).protectedAppDao()
            targets.forEach { runCatching { dao.delete(it) } }
        }
        for (pkg in targets) {
            runCatching { dpm.setUninstallBlocked(admin, pkg, false) }
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
        }
        app.otterling.restrictions.AccessibilityGuard
            .reapplyAllowlist(applicationContext)
        android.util.Log.e("MainActivity", "Cleared A2Y leftover DPM policies for $targets")
    }

    /**
     * DEBUG-ONLY: drop Device Owner so the app can be uninstalled.
     *   adb shell am start -n …/.MainActivity --ez clear_device_owner true -f 0x10008000
     * then: adb shell pm uninstall app.otterling
     */
    private fun applyDebugClearDeviceOwner(intent: Intent?) {
        if (!isDebuggable) return
        if (intent?.getBooleanExtra("clear_device_owner", false) != true) return
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return
        val admin = android.content.ComponentName(
            this,
            app.otterling.admin.DeviceAdminReceiverImpl::class.java,
        )
        runCatching {
            app.otterling.monitoring.ProtectionEnforcementService
                .stop(applicationContext)
        }
        runCatching {
            kotlinx.coroutines.runBlocking {
                app.otterling.monitoring.ProtectionController(applicationContext)
                    .shutdown()
            }
        }
        runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        runCatching { dpm.setUninstallBlocked(admin, packageName, false) }
        runCatching { dpm.clearDeviceOwnerApp(packageName) }
            .onSuccess { android.util.Log.e("MainActivity", "clearDeviceOwnerApp succeeded") }
            .onFailure { android.util.Log.e("MainActivity", "clearDeviceOwnerApp failed", it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugUnsuspend(intent)
        applyDebugClearA2yPolicies(intent)
        applyDebugClearDeviceOwner(intent)
        if (wantsDebugSettings(intent)) recreate()
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDebugUnsuspend(intent)
        applyDebugClearA2yPolicies(intent)
        applyDebugClearDeviceOwner(intent)
        requestNotificationPermissionIfNeeded()
        requestBatteryOptimizationExemptionIfNeeded()
        if (ProtectionController(applicationContext).isEnabled()) {
            ProtectionEnforcementService.start(applicationContext)
        }
        RestrictionEnforcementWorker.enqueuePeriodic(applicationContext)
        BlocklistRefreshWorker.enqueuePeriodic(applicationContext)
        UpdateCheckWorker.enqueuePeriodic(applicationContext)
        MacTamperPollWorker.enqueuePeriodic(applicationContext)
        // Hand our FCM token to the filter-server so it can push a "poll now" wake -- turns the
        // 15-minute poll floor into seconds. Idempotent and best-effort; falls back to polling.
        FcmTokenRegistrar.registerCurrentToken(applicationContext)
        setContent {
            FamilyGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember {
                        mutableStateOf(
                            when {
                                wantsDebugSettings(intent) -> Screen.Settings
                                else -> {
                                    val onboardingState = OnboardingState(applicationContext)
                                    // Upgrade path: an install from before this flag existed, that
                                    // already has everything configured, must not be forced through
                                    // the wizard just because the flag itself is new.
                                    if (!onboardingState.isComplete() &&
                                        resolveOnboardingStep(applicationContext) == OnboardingStep.Done
                                    ) {
                                        onboardingState.markComplete()
                                    }
                                    // Device Owner is re-checked live every launch, even once the
                                    // wizard has completed once: unlike restrictions/VPN/etc (which
                                    // ProtectionEnforcementService/RestrictionEnforcementWorker
                                    // reapply on their own if they drift), nothing can silently
                                    // restore Device Owner if it's lost -- only ADB/QR provisioning
                                    // from outside the app can. Without this check, a device that
                                    // lost Device Owner (debug "clear device owner" hook, `dpm
                                    // remove-active-admin`, factory reset, etc.) would fall straight
                                    // through to the normal Dashboard's passive banner forever,
                                    // exactly the "looks fine but isn't protected" state the wizard
                                    // exists to prevent.
                                    val deviceOwnerActive = DeviceOwnerManager(applicationContext)
                                        .currentStatus().isDeviceOwner
                                    if (onboardingState.isComplete() && deviceOwnerActive) Screen.Home else Screen.Onboarding
                                }
                            },
                        )
                    }
                    val pinAuthManager = remember { PinAuthManager(applicationContext) }

                    when (screen) {
                        Screen.Onboarding -> OnboardingWizard(
                            context = applicationContext,
                            onComplete = { screen = Screen.Home },
                        )
                        Screen.Home -> DashboardScreen(
                            context = applicationContext,
                            onOpenSettings = { screen = Screen.PinEntry },
                        )
                        Screen.PinEntry -> PinLockScreen(
                            pinAuthManager = pinAuthManager,
                            onUnlocked = { screen = Screen.Settings },
                            onCancel = { screen = Screen.Home },
                        )
                        Screen.Settings -> AppShell(
                            context = applicationContext,
                            batteryOptimizationLauncher = batteryOptimizationLauncher,
                            onExit = { screen = Screen.Home },
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        val batteryManager = BatteryOptimizationManager(applicationContext)
        if (!batteryManager.isExempt()) {
            batteryOptimizationLauncher.launch(batteryManager.exemptionRequestIntent())
        }
    }
}
