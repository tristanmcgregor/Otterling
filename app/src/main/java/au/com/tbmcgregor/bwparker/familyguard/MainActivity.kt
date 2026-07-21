package au.com.tbmcgregor.bwparker.familyguard

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceOwnerManager
import au.com.tbmcgregor.bwparker.familyguard.content.AppSuspensionManager
import au.com.tbmcgregor.bwparker.familyguard.content.PrivateDnsFilterManager
import au.com.tbmcgregor.bwparker.familyguard.data.BlockedApp
import au.com.tbmcgregor.bwparker.familyguard.knox.KnoxLicenseManager
import au.com.tbmcgregor.bwparker.familyguard.monitoring.ProtectionEnforcementService
import au.com.tbmcgregor.bwparker.familyguard.pin.PinAuthManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.Restriction
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEvent
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger
import au.com.tbmcgregor.bwparker.familyguard.ui.PinLockScreen
import au.com.tbmcgregor.bwparker.familyguard.ui.SettingsScreen
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private enum class Screen { Home, PinEntry, Settings }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        ProtectionEnforcementService.start(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.Home) }
                    val pinAuthManager = remember { PinAuthManager(applicationContext) }

                    when (screen) {
                        Screen.Home -> HomeScreen(onOpenSettings = { screen = Screen.PinEntry })
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
                            DeviceOwnerSection()
                            HorizontalDivider()
                            RestrictionsSection()
                            HorizontalDivider()
                            ContentFilterSection()
                            HorizontalDivider()
                            KnoxSetupSection()
                            HorizontalDivider()
                        }
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
                Text("Family Device Guard", style = MaterialTheme.typography.headlineMedium)
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

    @Composable
    private fun DeviceOwnerSection() {
        val ownerManager = remember { DeviceOwnerManager(applicationContext) }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        val status = remember(refreshTrigger) { ownerManager.currentStatus() }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phase 2 — Device Admin / Device Owner", style = MaterialTheme.typography.titleMedium)
            Text("Device admin active: ${status.isDeviceAdminActive}")
            Text("Device owner: ${status.isDeviceOwner}")

            if (!status.isDeviceOwner) {
                Text(
                    "Run on a factory-reset test device (before adding any Google account):",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(ownerManager.provisioningAdbCommand, fontFamily = FontFamily.Monospace)
            } else {
                Text(
                    "To rebuild/remove this admin later:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(ownerManager.removeAdminAdbCommand, fontFamily = FontFamily.Monospace)
            }

            OutlinedButton(onClick = { refreshTrigger++ }) {
                Text("Refresh status")
            }
        }
    }

    @Composable
    private fun RestrictionsSection() {
        val restrictionsManager = remember { DeviceRestrictionsManager(applicationContext) }
        val tamperLogger = remember { TamperEventLogger(applicationContext) }
        var refreshTrigger by remember { mutableIntStateOf(0) }
        var recentEvents by remember { mutableStateOf<List<TamperEvent>>(emptyList()) }
        val states = remember(refreshTrigger) {
            Restriction.entries.associateWith { restrictionsManager.isEnabled(it) }
        }
        val uninstallBlocked = remember(refreshTrigger) { restrictionsManager.isUninstallBlocked() }
        LaunchedEffect(refreshTrigger) {
            recentEvents = tamperLogger.recent()
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phase 3 — Tamper resistance", style = MaterialTheme.typography.titleMedium)
            Text(
                "Requires device owner (see above). Samsung's bootloader recovery-menu " +
                    "factory reset may need Knox on top of this — verify physically.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Block app uninstall")
                Switch(
                    checked = uninstallBlocked,
                    onCheckedChange = {
                        restrictionsManager.setUninstallBlocked(it)
                        refreshTrigger++
                    },
                )
            }

            states.forEach { (restriction, enabled) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(restriction.displayName)
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            restrictionsManager.setEnabled(restriction, it)
                            refreshTrigger++
                        },
                    )
                }
            }

            OutlinedButton(onClick = {
                restrictionsManager.applyDefaults()
                refreshTrigger++
            }) {
                Text("Enable all recommended protections")
            }

            Text("Recent tamper events", style = MaterialTheme.typography.bodyMedium)
            if (recentEvents.isEmpty()) {
                Text("No tamper events recorded.", style = MaterialTheme.typography.bodySmall)
            } else {
                recentEvents.forEach { event ->
                    val timestamp = DateFormat.getDateTimeInstance().format(Date(event.timestampMillis))
                    Text(
                        "$timestamp — ${event.details}",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
        var newPackageName by remember { mutableStateOf("") }
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phase 4 — Content filtering", style = MaterialTheme.typography.titleMedium)

            Text("DNS content filter (blocks adult content + Safe Search)")
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

            Text("Blocked apps")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newPackageName,
                    onValueChange = { newPackageName = it },
                    label = { Text("Package name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val packageName = newPackageName.trim()
                    if (packageName.isNotEmpty()) {
                        coroutineScope.launch {
                            suspensionManager.setBlocked(packageName, true)
                            newPackageName = ""
                            refreshTrigger++
                        }
                    }
                }) {
                    Text("Block")
                }
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

    private fun describeDnsResult(result: PrivateDnsFilterManager.Result): String = when (result) {
        PrivateDnsFilterManager.Result.Success -> "Applied"
        PrivateDnsFilterManager.Result.UnsupportedApiLevel -> "Requires Android 10+"
        is PrivateDnsFilterManager.Result.Failed -> "Failed: ${result.message}"
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phase 1 — Knox environment and license", style = MaterialTheme.typography.titleMedium)
            Text("License manager available: ${availability.licenseManager}")
            Text("RestrictionPolicy available: ${availability.restrictionPolicy}")
            Text("Activation: $activationStatus")
            Button(onClick = ::requestActivation) {
                Text("Activate Knox license")
            }
        }
    }
}
