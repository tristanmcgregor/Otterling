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
import au.com.tbmcgregor.bwparker.familyguard.content.VpnFilterManager
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

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var lockdownEnabled by remember { mutableStateOf(false) }
    var domainCount by remember { mutableIntStateOf(0) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    LaunchedEffect(refreshTrigger) {
        enabled = vpnManager.wasEnabledByUser()
        lockdownEnabled = vpnManager.isLockdownEnabled()
        domainCount = blocklistManager.domainCount()
        lastUpdated = blocklistManager.lastUpdatedMillis().takeIf { it > 0 }
    }

    SectionCard(
        title = "Content Filter VPN",
        icon = Icons.Default.VpnLock,
        subtitle = "Filters DNS lookups (and known DNS-over-HTTPS resolvers) against a downloaded " +
            "adult-content domain list. Doesn't decrypt or read any web page content -- see chat " +
            "for why. Once enabled, Android won't let this be turned off from Settings, and blocks " +
            "all network access if the filter isn't running.",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Status", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (enabled) "Enabled" else "Disabled", isGood = enabled)
        }
        if (enabled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lockdown (can't be bypassed)", style = MaterialTheme.typography.bodyLarge)
                StatusText(if (lockdownEnabled) "Active" else "Not active", isGood = lockdownEnabled)
            }
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
                            statusMessage = if (vpnManager.enable()) "Enabled." else "Failed -- is Device Owner active?"
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
                            statusMessage = if (vpnManager.disable()) "Disabled." else "Failed to disable."
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
    }
}
