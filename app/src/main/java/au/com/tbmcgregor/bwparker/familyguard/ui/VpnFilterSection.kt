package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import au.com.tbmcgregor.bwparker.familyguard.content.CloudFilterSettings
import au.com.tbmcgregor.bwparker.familyguard.content.DomainBlocklistManager
import au.com.tbmcgregor.bwparker.familyguard.content.VpnBypassManager
import au.com.tbmcgregor.bwparker.familyguard.content.VpnFilterManager
import au.com.tbmcgregor.bwparker.familyguard.content.VpnFilterService
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VpnFilterSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val vpnManager = remember { VpnFilterManager(context) }
    val blocklistManager = remember { DomainBlocklistManager(context) }
    val bypassManager = remember { VpnBypassManager(context) }
    val cloudFilterSettings = remember { CloudFilterSettings(context) }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var lockdownEnabled by remember { mutableStateOf(false) }
    var domainCount by remember { mutableIntStateOf(0) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var bypassPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showBypassPicker by remember { mutableStateOf(false) }
    var cloudHost by remember { mutableStateOf("") }
    var cloudPort by remember { mutableStateOf("") }
    var cloudEnabled by remember { mutableStateOf(false) }
    var cloudStatusMessage by remember { mutableStateOf("") }
    var cloudTestBusy by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        enabled = vpnManager.wasEnabledByUser()
        lockdownEnabled = vpnManager.isLockdownEnabled()
        domainCount = blocklistManager.domainCount()
        lastUpdated = blocklistManager.lastUpdatedMillis().takeIf { it > 0 }
        bypassPackages = bypassManager.bypassPackages()
        cloudHost = cloudFilterSettings.host()
        cloudPort = cloudFilterSettings.port().toString()
        cloudEnabled = cloudFilterSettings.isEnabled()
        if (installedApps.isEmpty() && bypassPackages.isNotEmpty()) {
            installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        }
    }

    fun appLabel(packageName: String): String =
        installedApps.find { it.packageName == packageName }?.label ?: packageName

    if (showBypassPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showBypassPicker = false },
            onSelect = { app ->
                bypassManager.add(app.packageName)
                bypassPackages = bypassManager.bypassPackages()
                if (enabled) VpnFilterService.reestablish(context)
                showBypassPicker = false
            },
        )
    }

    SectionCard(
        title = "NSFW / Content Filter VPN",
        icon = Icons.Default.VpnLock,
        subtitle = "Canopy-style content filtering: DNS (and known DNS-over-HTTPS resolvers) is " +
            "checked against a local adult-content domain list, then forwarded to your own cloud " +
            "filter server as the primary category filter. Doesn't decrypt or read any web page " +
            "content. Once enabled, Android won't let this be turned off from Settings, and locks " +
            "down all other network access (including other VPN apps) to routes through this " +
            "tunnel -- so nothing on this device can bypass it. Enabling this temporarily falls " +
            "the separate Private DNS filter back to opportunistic (and restores it when " +
            "disabled) so the two don't conflict.",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Status", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (enabled) "Enabled" else "Disabled", isGood = enabled)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lockdown", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (lockdownEnabled) "Active" else "Inactive", isGood = lockdownEnabled)
        }

        val lastUpdatedText = lastUpdated?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"
        Text("Local blocklist: $domainCount domains (updated: $lastUpdatedText)", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!enabled) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        coroutineScope.launch {
                            statusMessage = "Downloading blocklist..."
                            withContext(Dispatchers.IO) { blocklistManager.refresh() }
                            val didEnable = withContext(Dispatchers.IO) { vpnManager.enable() }
                            statusMessage = if (didEnable) "Enabled." else "Failed -- is Device Owner active?"
                            busy = false
                            refreshTrigger++
                        }
                    },
                ) {
                    Text("Enable content filter VPN")
                }
            } else {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        coroutineScope.launch {
                            val didDisable = withContext(Dispatchers.IO) { vpnManager.disable() }
                            statusMessage = if (didDisable) "Disabled." else "Failed to disable."
                            busy = false
                            refreshTrigger++
                        }
                    },
                ) {
                    Text("Disable")
                }
            }
        }

        HorizontalDivider()

        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                coroutineScope.launch {
                    statusMessage = "Downloading blocklist..."
                    val result = withContext(Dispatchers.IO) { blocklistManager.refresh() }
                    statusMessage = result.fold(
                        onSuccess = { "Updated: $it domains." },
                        onFailure = { "Failed: ${it.message}" },
                    )
                    busy = false
                    refreshTrigger++
                }
            },
        ) {
            Text("Refresh blocklist now")
        }
        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        Text("Cloud filter server", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Your own deployable AdGuard Home filter server (see filter-server/) -- used as the " +
                "primary DNS category filter once reachable. Falls back to a public resolver " +
                "only if this is off or unreachable; the local adult-domain list above still " +
                "applies either way.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Use cloud filter", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = cloudEnabled,
                onCheckedChange = {
                    cloudFilterSettings.setEnabled(it)
                    cloudEnabled = it
                    if (enabled) VpnFilterService.reestablish(context)
                },
            )
        }
        OutlinedTextField(
            value = cloudHost,
            onValueChange = { cloudHost = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Filter server host") },
            singleLine = true,
            placeholder = { Text("bartholomew.help") },
        )
        OutlinedTextField(
            value = cloudPort,
            onValueChange = { input -> if (input.all { it.isDigit() }) cloudPort = input },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Port") },
            singleLine = true,
            placeholder = { Text("53") },
        )
        Button(
            onClick = {
                cloudFilterSettings.setHost(cloudHost)
                cloudPort.toIntOrNull()?.let { cloudFilterSettings.setPort(it) }
                cloudStatusMessage = "Saved."
                if (enabled) VpnFilterService.reestablish(context)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save filter server")
        }
        OutlinedButton(
            enabled = !cloudTestBusy && cloudHost.isNotBlank(),
            onClick = {
                cloudTestBusy = true
                coroutineScope.launch {
                    cloudFilterSettings.setHost(cloudHost)
                    cloudPort.toIntOrNull()?.let { cloudFilterSettings.setPort(it) }
                    val reachable = withContext(Dispatchers.IO) { cloudFilterSettings.testReachable() }
                    cloudStatusMessage = if (reachable) "Filter server reachable." else "Filter server unreachable."
                    cloudTestBusy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test filter server")
        }
        if (cloudStatusMessage.isNotEmpty()) {
            Text(cloudStatusMessage, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        Text("Apps that bypass the VPN", style = MaterialTheme.typography.bodyLarge)
        Text(
            "These apps are routed over the normal network instead of through the filter, for apps " +
                "that break under any VPN (e.g. Android Auto). Their traffic is NOT content-filtered.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = !busy,
            onClick = {
                coroutineScope.launch {
                    if (installedApps.isEmpty()) {
                        installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                    }
                    showBypassPicker = true
                }
            },
        ) {
            Text("Add app to bypass")
        }
        if (bypassPackages.isEmpty()) {
            Text("No apps bypassing the VPN.", style = MaterialTheme.typography.bodySmall)
        } else {
            bypassPackages.forEach { pkg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(appLabel(pkg), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        bypassManager.remove(pkg)
                        bypassPackages = bypassManager.bypassPackages()
                        if (enabled) VpnFilterService.reestablish(context)
                    }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}
