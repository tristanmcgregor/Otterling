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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var domainCount by remember { mutableIntStateOf(0) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var bypassPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showBypassPicker by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        enabled = vpnManager.wasEnabledByUser()
        domainCount = blocklistManager.domainCount()
        lastUpdated = blocklistManager.lastUpdatedMillis().takeIf { it > 0 }
        bypassPackages = bypassManager.bypassPackages()
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
        title = "Content Filter VPN",
        icon = Icons.Default.VpnLock,
        subtitle = "Filters DNS lookups (and known DNS-over-HTTPS resolvers) against a downloaded " +
            "adult-content domain list. Doesn't decrypt or read any web page content -- see chat " +
            "for why. Once enabled, Android won't let this be turned off from Settings -- but " +
            "unlike a full lockdown VPN, if the filter service itself is killed, traffic falls " +
            "back to normal (unfiltered) rather than cutting off the internet entirely. Enabling " +
            "this temporarily falls the separate Private DNS filter back to opportunistic (and " +
            "restores it when disabled) so the two don't conflict.",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Status", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (enabled) "Enabled" else "Disabled", isGood = enabled)
        }

        val lastUpdatedText = lastUpdated?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"
        Text("Blocklist: $domainCount domains (updated: $lastUpdatedText)", style = MaterialTheme.typography.bodySmall)

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
