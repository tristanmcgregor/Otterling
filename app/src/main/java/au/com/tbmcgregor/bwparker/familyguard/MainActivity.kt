package au.com.tbmcgregor.bwparker.familyguard

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.Restriction
import au.com.tbmcgregor.bwparker.familyguard.schedule.ScheduleEnforcementWorker
import au.com.tbmcgregor.bwparker.familyguard.schedule.ScheduleEngine
import au.com.tbmcgregor.bwparker.familyguard.schedule.ScheduleRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScheduleEnforcementWorker.enqueuePeriodic(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text("Family Device Guard", style = MaterialTheme.typography.headlineMedium)
                        DeviceOwnerSection()
                        HorizontalDivider()
                        RestrictionsSection()
                        HorizontalDivider()
                        ContentFilterSection()
                        HorizontalDivider()
                        ScheduleSection()
                        HorizontalDivider()
                        KnoxSetupSection()
                    }
                }
            }
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
        var refreshTrigger by remember { mutableIntStateOf(0) }
        val states = remember(refreshTrigger) {
            Restriction.entries.associateWith { restrictionsManager.isEnabled(it) }
        }
        val uninstallBlocked = remember(refreshTrigger) { restrictionsManager.isUninstallBlocked() }

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

    private fun parseTimeToMinutes(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun formatTime(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun formatDaysMask(mask: Int): String =
        if (mask == ScheduleRule.ALL_DAYS_MASK) {
            "Every day"
        } else {
            DAY_LABELS.filterIndexed { index, _ -> (mask and (1 shl index)) != 0 }
                .joinToString(",")
                .ifEmpty { "No days" }
        }

    private companion object {
        val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    @Composable
    private fun ScheduleSection() {
        val coroutineScope = rememberCoroutineScope()
        val scheduleEngine = remember { ScheduleEngine(applicationContext) }

        var refreshTrigger by remember { mutableIntStateOf(0) }
        var rules by remember { mutableStateOf<List<ScheduleRule>>(emptyList()) }
        var label by remember { mutableStateOf("") }
        var packageNames by remember { mutableStateOf("") }
        var startTime by remember { mutableStateOf("21:00") }
        var endTime by remember { mutableStateOf("07:00") }
        var daysMask by remember { mutableIntStateOf(ScheduleRule.ALL_DAYS_MASK) }
        var statusMessage by remember { mutableStateOf("") }

        LaunchedEffect(refreshTrigger) {
            rules = scheduleEngine.rules()
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phase 4 — Scheduled access windows", style = MaterialTheme.typography.titleMedium)
            Text(
                "Blocks the listed packages during the window below (e.g. bedtime 21:00–07:00). " +
                    "Re-checked every 15 minutes by WorkManager; tap \"Apply now\" to test immediately.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Rule name (e.g. Bedtime)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = packageNames,
                onValueChange = { packageNames = it },
                label = { Text("Package names, comma-separated") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startTime,
                    onValueChange = { startTime = it },
                    label = { Text("Start (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = endTime,
                    onValueChange = { endTime = it },
                    label = { Text("End (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAY_LABELS.forEachIndexed { index, dayLabel ->
                    val bit = 1 shl index
                    val selected = (daysMask and bit) != 0
                    FilterChipLike(
                        text = dayLabel,
                        selected = selected,
                        onClick = { daysMask = daysMask xor bit },
                    )
                }
            }

            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val start = parseTimeToMinutes(startTime)
                    val end = parseTimeToMinutes(endTime)
                    if (label.isBlank() || packageNames.isBlank() || start == null || end == null) {
                        statusMessage = "Fill in name, packages, and valid HH:mm times"
                    } else {
                        coroutineScope.launch {
                            scheduleEngine.upsert(
                                ScheduleRule(
                                    label = label,
                                    daysOfWeekMask = daysMask,
                                    startMinuteOfDay = start,
                                    endMinuteOfDay = end,
                                    packageNames = packageNames,
                                ),
                            )
                            label = ""
                            packageNames = ""
                            statusMessage = "Added"
                            refreshTrigger++
                        }
                    }
                }) {
                    Text("Add rule")
                }
                OutlinedButton(onClick = {
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) { scheduleEngine.applyNow() }
                        statusMessage = "Applied now"
                        refreshTrigger++
                    }
                }) {
                    Text("Apply now")
                }
            }

            rules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.label)
                        Text(
                            "${formatTime(rule.startMinuteOfDay)}–${formatTime(rule.endMinuteOfDay)} · " +
                                formatDaysMask(rule.daysOfWeekMask) + " · " + rule.packageNames,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        coroutineScope.launch {
                            scheduleEngine.delete(rule.id)
                            refreshTrigger++
                        }
                    }) {
                        Text("Delete")
                    }
                }
            }
        }
    }

    @Composable
    private fun FilterChipLike(text: String, selected: Boolean, onClick: () -> Unit) {
        val label = if (selected) "[$text]" else text
        TextButton(onClick = onClick) {
            Text(label)
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
