package app.otterling

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.otterling.admin.DeviceOwnerManager
import app.otterling.content.AppSuspensionManager
import app.otterling.content.BlocklistRefreshWorker
import app.otterling.content.PrivateDnsFilterManager
import app.otterling.data.BlockedApp
import app.otterling.data.ProtectedApp
import app.otterling.knox.KnoxLicenseManager
import app.otterling.monitoring.ProtectionController
import app.otterling.monitoring.ProtectionEnforcementService
import app.otterling.onboarding.OnboardingState
import app.otterling.onboarding.OnboardingStep
import app.otterling.onboarding.resolveOnboardingStep
import app.otterling.pin.PinAuthManager
import app.otterling.updates.UpdateCheckWorker
import app.otterling.restrictions.AppUninstallGuard
import app.otterling.restrictions.BatteryOptimizationManager
import app.otterling.restrictions.DeviceRestrictionsManager
import app.otterling.restrictions.PackageDisableStore
import app.otterling.restrictions.Restriction
import app.otterling.restrictions.RestrictionEnforcementWorker
import app.otterling.tamper.TamperEvent
import app.otterling.tamper.TamperEventLogger
import app.otterling.ui.AccessibilityServiceSection
import app.otterling.ui.AppPickerDialog
import app.otterling.ui.BlockedWebsitesSettingsSection
import app.otterling.ui.DashboardScreen
import app.otterling.ui.GuardianSmsAlertsSection
import app.otterling.ui.HabitShareSettingsScreen
import app.otterling.ui.InstalledAppInfo
import app.otterling.ui.MindfulAppsSection
import app.otterling.ui.OnboardingWizard
import app.otterling.ui.PinLockScreen
import app.otterling.ui.SectionCard
import app.otterling.ui.SettingsScreen
import app.otterling.ui.TimeBudgetsSection
import app.otterling.ui.UpdateSection
import app.otterling.ui.VpnFilterSection
import app.otterling.ui.theme.FamilyGuardTheme
import app.otterling.ui.loadInstalledApps
import app.otterling.ui.StatusText
import app.otterling.ui.SwitchRow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private enum class Screen { Home, Onboarding, PinEntry, Settings, HabitShareSettings }

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
                        Screen.Settings -> SettingsScreen(
                            onBack = { screen = Screen.Home },
                            onChangePin = {
                                pinAuthManager.clearPin()
                                screen = Screen.PinEntry
                            },
                        ) {
                            ProtectionControlSection()
                            GuardianSmsAlertsSection(applicationContext)
                            DeviceOwnerSection()
                            RestrictionsSection()
                            UninstallProtectionSection()
                            ContentFilterSection()
                            DisabledAppsSection()
                            VpnFilterSection(applicationContext)
                            BlockedWebsitesSettingsSection(applicationContext)
                            AccessibilityServiceSection(applicationContext)
                            MindfulAppsSection(applicationContext)
                            TimeBudgetsSection(applicationContext)
                            HabitShareNavSection(onOpen = { screen = Screen.HabitShareSettings })
                            KnoxSetupSection()
                            UpdateSection(applicationContext)
                        }
                        Screen.HabitShareSettings -> HabitShareSettingsScreen(
                            context = applicationContext,
                            onBack = { screen = Screen.Settings },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeScreen(onOpenSettings: () -> Unit) {
        val ownerManager = remember { DeviceOwnerManager(applicationContext) }
        val restrictionsManager = remember { DeviceRestrictionsManager(applicationContext) }
        val status = remember { ownerManager.currentStatus() }
        val activeRestrictions = remember {
            Restriction.entries.count { restrictionsManager.isEnabled(it) } +
                if (restrictionsManager.isUninstallBlocked()) 1 else 0
        }
        val totalRestrictions = Restriction.entries.size + 1

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Otterling", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Parental controls for this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HomeStatusCard(
                isDeviceOwner = status.isDeviceOwner,
                activeRestrictions = activeRestrictions,
                totalRestrictions = totalRestrictions,
            )

            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Settings")
            }
        }
    }

    @Composable
    private fun HomeStatusCard(isDeviceOwner: Boolean, activeRestrictions: Int, totalRestrictions: Int) {
        val containerColor = if (isDeviceOwner) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
        val contentColor = if (isDeviceOwner) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }

        Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (isDeviceOwner) "Protected" else "Setup required",
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor,
                )
                Text(
                    if (isDeviceOwner) {
                        "$activeRestrictions of $totalRestrictions tamper protections active"
                    } else {
                        "Device Owner hasn't been set up yet. Open Settings to finish setup."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
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

    @Composable
    private fun ProtectionControlSection() {
        val coroutineScope = rememberCoroutineScope()
        val controller = remember { ProtectionController(applicationContext) }
        var protectionEnabled by remember { mutableStateOf(controller.isEnabled()) }
        var showConfirmOff by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }

        SectionCard(
            title = "Protection",
            icon = Icons.Default.PowerSettingsNew,
            subtitle = if (protectionEnabled) {
                "Habit rules, time budgets, VPN filtering, app blocking, and tamper protections are active."
            } else {
                "Everything is off — apps unsuspended/re-enabled, VPN stopped, and tamper protections disabled."
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Status", style = MaterialTheme.typography.bodyLarge)
                StatusText(if (protectionEnabled) "Active" else "Off", isGood = protectionEnabled)
            }

            if (protectionEnabled) {
                OutlinedButton(
                    onClick = { showConfirmOff = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Turn protection off")
                }
            } else {
                Button(
                    onClick = {
                        busy = true
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) { controller.startup() }
                            protectionEnabled = true
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Turning on..." else "Turn protection on")
                }
            }
        }

        if (showConfirmOff) {
            AlertDialog(
                onDismissRequest = { showConfirmOff = false },
                title = { Text("Turn protection off?") },
                text = {
                    Text(
                        "This stops habit rules, time budgets, the filter VPN, friction screens, " +
                            "unsuspends every blocked app, re-enables any apps that were disabled, " +
                            "and turns off tamper protections (safe mode, factory reset, USB debugging " +
                            "block, uninstall block, and protected apps). Turn protection back on here " +
                            "to restore everything from your saved settings.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirmOff = false
                            busy = true
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) { controller.shutdown() }
                                protectionEnabled = false
                                busy = false
                            }
                        },
                    ) {
                        Text("Turn off")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmOff = false }) { Text("Cancel") }
                },
            )
        }
    }

    @Composable
    private fun DeviceOwnerSection() {
        val ownerManager = remember { DeviceOwnerManager(applicationContext) }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        val status = remember(refreshTrigger) { ownerManager.currentStatus() }

        SectionCard(title = "Device Owner Setup", icon = Icons.Default.AdminPanelSettings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Device admin", style = MaterialTheme.typography.bodyLarge)
                StatusText(
                    if (status.isDeviceAdminActive) "Active" else "Not active",
                    isGood = status.isDeviceAdminActive,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Device owner", style = MaterialTheme.typography.bodyLarge)
                StatusText(
                    if (status.isDeviceOwner) "Active" else "Not active",
                    isGood = status.isDeviceOwner,
                )
            }

            if (!status.isDeviceOwner) {
                Text(
                    "Run on a factory-reset test device (before adding any Google account):",
                    style = MaterialTheme.typography.bodySmall,
                )
                CodeBlock(ownerManager.provisioningAdbCommand)
            } else {
                Text(
                    "To rebuild/remove this admin later:",
                    style = MaterialTheme.typography.bodySmall,
                )
                CodeBlock(ownerManager.removeAdminAdbCommand)
            }

            OutlinedButton(onClick = { refreshTrigger++ }) {
                Text("Refresh status")
            }
        }
    }

    @Composable
    private fun CodeBlock(text: String) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
            )
        }
    }

    @Composable
    private fun RestrictionsSection() {
        val restrictionsManager = remember { DeviceRestrictionsManager(applicationContext) }
        val tamperLogger = remember { TamperEventLogger(applicationContext) }
        val batteryManager = remember { BatteryOptimizationManager(applicationContext) }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        var recentEvents by remember { mutableStateOf<List<TamperEvent>>(emptyList()) }
        val states = remember(refreshTrigger) {
            Restriction.entries.associateWith { restrictionsManager.isEnabled(it) }
        }
        val uninstallBlocked = remember(refreshTrigger) { restrictionsManager.isUninstallBlocked() }
        val batteryExempt = remember(refreshTrigger) { batteryManager.isExempt() }
        LaunchedEffect(refreshTrigger) {
            recentEvents = tamperLogger.recent(5)
        }

        SectionCard(
            title = "Tamper Protection",
            icon = Icons.Default.Shield,
            subtitle = "Requires Device Owner (see above). Samsung's bootloader recovery-menu " +
                "factory reset may need Knox on top of this — verify physically.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Battery optimization exemption", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Keeps enforcement running in the background without Samsung freezing it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (batteryExempt) {
                    StatusText("Exempt", isGood = true)
                } else {
                    Button(onClick = {
                        batteryOptimizationLauncher.launch(batteryManager.exemptionRequestIntent())
                        refreshTrigger++
                    }) {
                        Text("Grant")
                    }
                }
            }

            HorizontalDivider()

            SwitchRow(
                label = "Block app uninstall",
                checked = uninstallBlocked,
                onCheckedChange = {
                    restrictionsManager.setUninstallBlocked(it)
                    refreshTrigger++
                },
                description = "Require Device Owner to remove protected apps",
                emphasizeLabel = true,
            )

            states.forEach { (restriction, enabled) ->
                SwitchRow(
                    label = restriction.displayName,
                    checked = enabled,
                    onCheckedChange = {
                        restrictionsManager.setEnabled(restriction, it)
                        refreshTrigger++
                    },
                )
            }

            OutlinedButton(onClick = {
                restrictionsManager.applyDefaults()
                refreshTrigger++
            }) {
                Text("Enable all recommended protections")
            }

            HorizontalDivider()

            Text("Recent tamper events", style = MaterialTheme.typography.bodyMedium)
            if (recentEvents.isEmpty()) {
                Text("None.", style = MaterialTheme.typography.bodySmall)
            } else {
                recentEvents.take(5).forEach { event ->
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestampMillis))
                    Text(
                        "$time · ${event.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun UninstallProtectionSection() {
        val coroutineScope = rememberCoroutineScope()
        val uninstallGuard = remember { AppUninstallGuard(applicationContext) }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        var protectedApps by remember { mutableStateOf<List<ProtectedApp>>(emptyList()) }
        var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
        var showAppPicker by remember { mutableStateOf(false) }
        LaunchedEffect(refreshTrigger) {
            protectedApps = uninstallGuard.protectedApps()
        }

        if (showAppPicker) {
            AppPickerDialog(
                apps = installedApps,
                onDismiss = { showAppPicker = false },
                onSelect = { app ->
                    coroutineScope.launch {
                        uninstallGuard.protect(app.packageName)
                        refreshTrigger++
                    }
                    showAppPicker = false
                },
            )
        }

        SectionCard(
            title = "Protect Apps From Uninstall",
            icon = Icons.Default.VerifiedUser,
            subtitle = "These apps can't be uninstalled while Device Owner is active.",
        ) {
            Button(onClick = {
                coroutineScope.launch {
                    installedApps = withContext(Dispatchers.IO) { loadInstalledApps(applicationContext) }
                    showAppPicker = true
                }
            }) {
                Text("Choose app to protect")
            }
            if (protectedApps.isEmpty()) {
                Text("No apps protected yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                protectedApps.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app.packageName, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            coroutineScope.launch {
                                uninstallGuard.unprotect(app.packageName)
                                refreshTrigger++
                            }
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ContentFilterSection() {
        val coroutineScope = rememberCoroutineScope()
        val suspensionManager = remember { AppSuspensionManager(applicationContext) }
        val dnsManager = remember { PrivateDnsFilterManager(applicationContext) }

        var refreshTrigger by remember { mutableIntStateOf(0) }
        var blockedApps by remember { mutableStateOf<List<BlockedApp>>(emptyList()) }
        var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
        var showAppPicker by remember { mutableStateOf(false) }
        var dnsStatus by remember { mutableStateOf("Checking…") }

        LaunchedEffect(refreshTrigger) {
            blockedApps = suspensionManager.blockedApps()
            dnsStatus = when {
                !dnsManager.isSupported ->
                    "Requires Android 10+ (this device: API ${Build.VERSION.SDK_INT})"
                else -> withContext(Dispatchers.IO) { dnsManager.currentHost() }
                    ?.let { "Active: $it" }
                    ?: "Not set"
            }
        }

        if (showAppPicker) {
            AppPickerDialog(
                apps = installedApps,
                onDismiss = { showAppPicker = false },
                onSelect = { app ->
                    coroutineScope.launch {
                        suspensionManager.setBlocked(app.packageName, true)
                        refreshTrigger++
                    }
                    showAppPicker = false
                },
            )
        }

        SectionCard(title = "Content Filtering", icon = Icons.Default.FilterAlt) {
            Text("DNS content filter (blocks adult content + Safe Search)", style = MaterialTheme.typography.bodyLarge)
            Text(dnsStatus, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            dnsManager.enable(PrivateDnsFilterManager.FilterProfile.FAMILY)
                        }
                        dnsStatus = describeDnsResult(result)
                        refreshTrigger++
                    }
                }) {
                    Text("Enable")
                }
                OutlinedButton(onClick = {
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) { dnsManager.disable() }
                        dnsStatus = describeDnsResult(result)
                        refreshTrigger++
                    }
                }) {
                    Text("Disable")
                }
            }

            HorizontalDivider()

            Text("Blocked apps", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = {
                coroutineScope.launch {
                    installedApps = withContext(Dispatchers.IO) { loadInstalledApps(applicationContext) }
                    showAppPicker = true
                }
            }) {
                Text("Choose app to block")
            }

            blockedApps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.packageName, modifier = Modifier.weight(1f))
                    Switch(
                        checked = app.blocked,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                suspensionManager.setBlocked(app.packageName, checked)
                                refreshTrigger++
                            }
                        },
                    )
                    TextButton(onClick = {
                        coroutineScope.launch {
                            suspensionManager.remove(app.packageName)
                            refreshTrigger++
                        }
                    }) {
                        Text("Remove")
                    }
                }
            }
        }
    }

    @Composable
    private fun DisabledAppsSection() {
        val coroutineScope = rememberCoroutineScope()
        val disableStore = remember { PackageDisableStore(applicationContext) }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        var entries by remember { mutableStateOf<List<PackageDisableStore.Entry>>(emptyList()) }

        LaunchedEffect(refreshTrigger) {
            entries = withContext(Dispatchers.IO) { disableStore.visibleEntries() }
        }

        SectionCard(
            title = "Disabled apps",
            icon = Icons.Default.Block,
            subtitle = "Apps blocked by habit rules or the hide/disable fallback. Undisable " +
                "unsuspends them and stops automatic re-block until you tap Disable again.",
        ) {
            if (entries.isEmpty()) {
                Text("No apps tracked as blocked.", style = MaterialTheme.typography.bodySmall)
            }
            entries.forEach { entry ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                entry.blocked -> "Blocked"
                                entry.exempt -> "Undisabled by you"
                                else -> entry.packageName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        disableStore.undisable(entry.packageName)
                                    }
                                    refreshTrigger++
                                }
                            },
                        ) {
                            Text("Undisable")
                        }
                        if (!entry.blocked) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            disableStore.disable(entry.packageName)
                                        }
                                        refreshTrigger++
                                    }
                                },
                            ) {
                                Text("Disable again")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun describeDnsResult(result: PrivateDnsFilterManager.Result): String = when (result) {
        PrivateDnsFilterManager.Result.Success -> "Applied"
        PrivateDnsFilterManager.Result.UnsupportedApiLevel -> "Requires Android 10+"
        is PrivateDnsFilterManager.Result.Failed -> "Failed: ${result.message}"
    }

    @Composable
    private fun HabitShareNavSection(onOpen: () -> Unit) {
        SectionCard(
            title = "HabitShare",
            icon = Icons.Default.CheckCircle,
            subtitle = "Connect your account, choose which habits need image proof, and build " +
                "habit-based app-unlock rules.",
        ) {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Open HabitShare settings")
            }
        }
    }

    @Composable
    private fun KnoxSetupSection() {
        val licenseManager = remember { KnoxLicenseManager(applicationContext) }
        val availability = remember { licenseManager.checkAvailability() }
        var activationStatus by remember { mutableStateOf("Not requested") }

        fun requestActivation() {
            activationStatus = when (
                val result = licenseManager.activate(BuildConfig.KNOX_LICENSE_KEY)
            ) {
                KnoxLicenseManager.ActivationResult.Requested ->
                    "Requested — check KnoxLicenseReceiver in Logcat"
                KnoxLicenseManager.ActivationResult.MissingKey ->
                    "No key configured"
                KnoxLicenseManager.ActivationResult.KnoxUnavailable ->
                    "Knox API unavailable on this device"
                is KnoxLicenseManager.ActivationResult.Failed ->
                    "Request failed: ${result.message}"
            }
        }

        LaunchedEffect(Unit) {
            if (BuildConfig.KNOX_LICENSE_KEY.isNotBlank()) {
                requestActivation()
            }
        }

        SectionCard(
            title = "Knox License (Advanced)",
            icon = Icons.Default.VerifiedUser,
            subtitle = "Not required for the features above — reserved for future Knox-backed protections.",
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("License manager", style = MaterialTheme.typography.bodyLarge)
                StatusText(
                    if (availability.licenseManager) "Available" else "Unavailable",
                    isGood = availability.licenseManager,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RestrictionPolicy", style = MaterialTheme.typography.bodyLarge)
                StatusText(
                    if (availability.restrictionPolicy) "Available" else "Unavailable",
                    isGood = availability.restrictionPolicy,
                )
            }
            Text("Activation: $activationStatus", style = MaterialTheme.typography.bodySmall)
            Button(onClick = ::requestActivation) {
                Text("Activate Knox license")
            }
        }
    }
}
