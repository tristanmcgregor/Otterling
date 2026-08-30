package app.otterling.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import app.otterling.BuildConfig
import app.otterling.admin.DeviceOwnerManager
import app.otterling.content.AppSuspensionManager
import app.otterling.data.ProtectedApp
import app.otterling.knox.KnoxLicenseManager
import app.otterling.monitoring.ProtectionController
import app.otterling.restrictions.AppUninstallGuard
import app.otterling.restrictions.BatteryOptimizationManager
import app.otterling.restrictions.DeviceRestrictionsManager
import app.otterling.restrictions.PackageDisableStore
import app.otterling.restrictions.Restriction
import app.otterling.restrictions.RestrictionPreferences
import app.otterling.tamper.TamperEvent
import app.otterling.tamper.TamperEventLogger
import app.otterling.ui.AppPickerDialog
import app.otterling.ui.InstalledAppInfo
import app.otterling.ui.SectionCard
import app.otterling.ui.StatusText
import app.otterling.ui.SwitchRow
import app.otterling.ui.loadInstalledApps
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Device-administration sections shown on the app shell's Settings tab. Extracted out of
 * MainActivity so they're plain, context-parameterized composables the nav shell can host
 * directly, rather than private methods tied to the Activity instance.
 */

@Composable
fun ProtectionControlSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val controller = remember { ProtectionController(context) }
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
fun DeviceOwnerSection(context: Context) {
    val ownerManager = remember { DeviceOwnerManager(context) }
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
fun RestrictionsSection(
    context: Context,
    batteryOptimizationLauncher: ActivityResultLauncher<Intent>,
) {
    val restrictionsManager = remember { DeviceRestrictionsManager(context) }
    val tamperLogger = remember { TamperEventLogger(context) }
    val batteryManager = remember { BatteryOptimizationManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var recentEvents by remember { mutableStateOf<List<TamperEvent>>(emptyList()) }
    val states = remember(refreshTrigger) {
        Restriction.entries.associateWith { restrictionsManager.isEnabled(it) }
    }
    val uninstallBlocked = remember(refreshTrigger) { restrictionsManager.isUninstallBlocked() }
    val restrictionPreferences = remember { RestrictionPreferences(context) }
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
            description = if (restrictionPreferences.isUninstallBlockDashboardManaged()) {
                "Managed by dashboard — a change here reverts automatically within 5 minutes"
            } else {
                "Require Device Owner to remove protected apps"
            },
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
                description = if (restrictionPreferences.isDashboardManaged(restriction)) {
                    "Managed by dashboard — a change here reverts automatically within 5 minutes"
                } else {
                    null
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
fun UninstallProtectionSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val uninstallGuard = remember { AppUninstallGuard(context) }
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
                installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
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
fun DisabledAppsSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val disableStore = remember { PackageDisableStore(context) }
    val suspensionManager = remember { AppSuspensionManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<PackageDisableStore.Entry>>(emptyList()) }
    var dashboardManaged by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(refreshTrigger) {
        entries = withContext(Dispatchers.IO) { disableStore.visibleEntries() }
        dashboardManaged = entries.map { it.packageName }
            .filter { suspensionManager.isDashboardManaged(it) }
            .toSet()
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
                if (entry.packageName in dashboardManaged) {
                    Text(
                        "Managed by dashboard -- Undisable won't stick past the next check-in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
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
}

@Composable
fun HabitShareNavSection(onOpen: () -> Unit) {
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
fun KnoxSetupSection(context: Context) {
    val licenseManager = remember { KnoxLicenseManager(context) }
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
