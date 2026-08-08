package app.otterling.ui

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
import app.otterling.content.CloudFilterSettings
import app.otterling.content.DomainBlocklistManager
import app.otterling.content.MitmExemptManager
import app.otterling.content.VpnFilterManager
import app.otterling.content.VpnFilterService
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
    val exemptManager = remember { MitmExemptManager(context) }
    val cloudFilterSettings = remember { CloudFilterSettings(context) }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var lockdownEnabled by remember { mutableStateOf(false) }
    var domainCount by remember { mutableIntStateOf(0) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var exemptPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showExemptPicker by remember { mutableStateOf(false) }
    var cloudHost by remember { mutableStateOf("") }
    var cloudPort by remember { mutableStateOf("") }
    var cloudEnabled by remember { mutableStateOf(false) }
    var cloudStatusMessage by remember { mutableStateOf("") }
    var cloudTestBusy by remember { mutableStateOf(false) }
    var proxyEnabled by remember { mutableStateOf(false) }
    var proxyPort by remember { mutableStateOf("") }
    var proxyUser by remember { mutableStateOf("") }
    var proxyPassword by remember { mutableStateOf("") }
    var proxyStatusMessage by remember { mutableStateOf("") }
    var proxyTestBusy by remember { mutableStateOf(false) }
    var caInstalled by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        enabled = vpnManager.wasEnabledByUser()
        lockdownEnabled = vpnManager.isLockdownEnabled()
        domainCount = blocklistManager.domainCount()
        lastUpdated = blocklistManager.lastUpdatedMillis().takeIf { it > 0 }
        exemptPackages = exemptManager.exemptPackages()
        cloudHost = cloudFilterSettings.host()
        cloudPort = cloudFilterSettings.port().toString()
        cloudEnabled = cloudFilterSettings.isEnabled()
        proxyEnabled = cloudFilterSettings.isProxyEnabled()
        proxyPort = cloudFilterSettings.proxyPort().toString()
        proxyUser = cloudFilterSettings.proxyUser()
        proxyPassword = cloudFilterSettings.proxyPassword()
        caInstalled = withContext(Dispatchers.IO) { vpnManager.isCaCertInstalled() }
        if (installedApps.isEmpty() && exemptPackages.isNotEmpty()) {
            installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        }
    }

    fun appLabel(packageName: String): String =
        installedApps.find { it.packageName == packageName }?.label ?: packageName

    if (showExemptPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showExemptPicker = false },
            onSelect = { app ->
                exemptManager.add(app.packageName)
                exemptPackages = exemptManager.exemptPackages()
                if (enabled) VpnFilterService.reestablish(context)
                showExemptPicker = false
            },
        )
    }

    SectionCard(
        title = "Filter proxy (traffic via your server)",
        icon = Icons.Default.VpnLock,
        subtitle = "Web traffic is routed through your own server for filtering: DNS is checked " +
            "against a local adult-content domain list first, then TCP 80/443 (and QUIC/HTTP3 is " +
            "dropped so it can't sidestep this over HTTP/3) is CONNECT-proxied through your " +
            "mitmproxy filter server, which decides whether to block whole requests/pages " +
            "server-side -- not scrubbed in-page, and not DNS-only. Once enabled, Android won't " +
            "let this be turned off from Settings, and locks down all other network access " +
            "(including other VPN apps) to routes through this tunnel -- so nothing on this " +
            "device can bypass it. Enabling this temporarily falls the separate Private DNS " +
            "filter back to opportunistic (and restores it when disabled) so the two don't " +
            "conflict.",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Status", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (enabled) "Enabled" else "Disabled", isGood = enabled)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lockdown", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (lockdownEnabled) "Active" else "Inactive", isGood = lockdownEnabled)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Proxy CA installed", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (caInstalled) "Installed" else "Not installed", isGood = caInstalled)
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
            placeholder = { Text("vpn.bartholomew.help") },
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

        Text("Filter proxy (HTTPS interception)", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Routes TCP 80/443 through the mitmproxy filter server above (same host, its own " +
                "port) so whole pages can be blocked server-side, not just DNS-blocked. Requires " +
                "the proxy's CA cert to be trusted device-wide (see \"Proxy CA installed\" above) " +
                "-- installed automatically once Device Owner is active. If the proxy is " +
                "unreachable, HTTPS sites fail to load rather than silently bypassing it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Use filter proxy", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = proxyEnabled,
                onCheckedChange = {
                    cloudFilterSettings.setProxyEnabled(it)
                    proxyEnabled = it
                    if (enabled) VpnFilterService.reestablish(context)
                },
            )
        }
        OutlinedTextField(
            value = proxyPort,
            onValueChange = { input -> if (input.all { it.isDigit() }) proxyPort = input },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Proxy port") },
            singleLine = true,
            placeholder = { Text("8080") },
        )
        OutlinedTextField(
            value = proxyUser,
            onValueChange = { proxyUser = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Proxy username") },
            singleLine = true,
        )
        OutlinedTextField(
            value = proxyPassword,
            onValueChange = { proxyPassword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Proxy password") },
            singleLine = true,
        )
        Button(
            onClick = {
                proxyPort.toIntOrNull()?.let { cloudFilterSettings.setProxyPort(it) }
                cloudFilterSettings.setProxyUser(proxyUser)
                cloudFilterSettings.setProxyPassword(proxyPassword)
                proxyStatusMessage = "Saved."
                if (enabled) VpnFilterService.reestablish(context)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save filter proxy")
        }
        OutlinedButton(
            enabled = !proxyTestBusy && cloudHost.isNotBlank(),
            onClick = {
                proxyTestBusy = true
                coroutineScope.launch {
                    // Same as "Test filter server": persist the host field before probing, otherwise
                    // editing the host box and tapping Test proxy still hits the previously saved host.
                    cloudFilterSettings.setHost(cloudHost)
                    proxyPort.toIntOrNull()?.let { cloudFilterSettings.setProxyPort(it) }
                    cloudFilterSettings.setProxyUser(proxyUser)
                    cloudFilterSettings.setProxyPassword(proxyPassword)
                    val reachable = withContext(Dispatchers.IO) { cloudFilterSettings.testProxyReachable() }
                    proxyStatusMessage = if (reachable) "Proxy reachable." else "Proxy unreachable (check host/port/credentials)."
                    proxyTestBusy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test proxy")
        }
        if (proxyStatusMessage.isNotEmpty()) {
            Text(proxyStatusMessage, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        Text("Apps exempt from HTTPS filtering", style = MaterialTheme.typography.bodyLarge)
        Text(
            "These apps use certificate pinning (e.g. YouTube, banking apps), which breaks under " +
                "any HTTPS interception, not just ours. They stay inside the VPN -- DNS-level " +
                "filtering (the local blocklist and your cloud filter server above) still applies " +
                "to them -- but their HTTPS traffic skips content inspection, so pages/paths within " +
                "these apps aren't keyword-filtered. YouTube and common AU banking apps are " +
                "exempted by default; YouTube Shorts and other path-based rules still apply " +
                "separately via accessibility, since those don't need HTTPS interception at all.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            enabled = !busy,
            onClick = {
                coroutineScope.launch {
                    if (installedApps.isEmpty()) {
                        installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                    }
                    showExemptPicker = true
                }
            },
        ) {
            Text("Add exempt app")
        }
        if (exemptPackages.isEmpty()) {
            Text("No apps exempted.", style = MaterialTheme.typography.bodySmall)
        } else {
            exemptPackages.forEach { pkg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(appLabel(pkg), modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        exemptManager.remove(pkg)
                        exemptPackages = exemptManager.exemptPackages()
                        if (enabled) VpnFilterService.reestablish(context)
                    }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}
