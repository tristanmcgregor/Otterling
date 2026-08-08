package app.otterling.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.otterling.BuildConfig
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.AlertSeverity
import app.otterling.updates.ApprovedUpdateManager
import app.otterling.updates.UpdateCheckResult
import app.otterling.updates.UpdateCheckWorker
import app.otterling.updates.UpdateManifest
import app.otterling.updates.UpdateSettings
import kotlinx.coroutines.launch

/**
 * Overflow-menu / quick entry for the same gated update path as [UpdateSection].
 * Auto-checks on open; still requires the pinned release cert + manifest SHA checks.
 * Also shows other monorepo components from `/updates/index.json` (e.g. filter-server deploy).
 */
@Composable
fun CheckForUpdatesDialog(context: Context, onDismiss: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val updateManager = remember { ApprovedUpdateManager(context) }
    var checking by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("Checking…") }
    var availableManifest by remember { mutableStateOf<UpdateManifest?>(null) }
    var componentLines by remember { mutableStateOf<List<String>>(emptyList()) }

    fun runCheck() {
        checking = true
        statusMessage = "Checking…"
        availableManifest = null
        coroutineScope.launch {
            componentLines = updateManager.fetchComponentSummaries()
            when (val result = updateManager.checkForUpdate()) {
                is UpdateCheckResult.UpToDate -> statusMessage = "Already up to date."
                is UpdateCheckResult.UpdateAvailable -> {
                    availableManifest = result.manifest
                    statusMessage = "Update available: ${result.manifest.versionName}"
                }
                is UpdateCheckResult.Error -> statusMessage = "Check failed: ${result.message}"
            }
            checking = false
        }
    }

    LaunchedEffect(Unit) { runCheck() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("App updates") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (BuildConfig.RELEASE_CERT_SHA256.isBlank()) {
                    Text(
                        "This build has no pinned release certificate, so it cannot install updates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                if (componentLines.isNotEmpty()) {
                    Text("Server components", style = MaterialTheme.typography.labelLarge)
                    componentLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
                availableManifest?.let { manifest ->
                    Button(
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // Enqueues the same background worker the periodic check uses, rather
                            // than downloading/verifying/installing inline here -- so this dialog
                            // can close immediately instead of the user staring at it through a
                            // full download. UpdateInstallResultReceiver posts a notification once
                            // it's actually done.
                            UpdateCheckWorker.enqueueOneShot(context)
                            statusMessage = "Installing v${manifest.versionName} in the background — " +
                                "you'll get a notification when it's done."
                        },
                    ) {
                        Text("Install verified update (${manifest.versionName})")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = { runCheck() }, enabled = !checking) {
                Text("Check again")
            }
        },
    )
}

@Composable
fun UpdateSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val updateManager = remember { ApprovedUpdateManager(context) }
    val updateSettings = remember { UpdateSettings(context) }

    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var issuesUrl by remember { mutableStateOf(updateSettings.githubIssuesUrl()) }
    var requestStatusMessage by remember { mutableStateOf("") }

    SectionCard(
        title = "App updates",
        icon = Icons.Default.SystemUpdate,
        subtitle = "The only way this app updates itself: a signed build that passed AI review " +
            "against scripts/update_review_checklist.md and was published by CI to the family's " +
            "own update server. There is no way to install a locally-built or otherwise-unsigned " +
            "APK from this screen -- that would defeat the VPN lockdown, proxy fail-closed " +
            "behavior, and every other protection in this app.",
    ) {
        Text("Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
        if (BuildConfig.RELEASE_CERT_SHA256.isBlank()) {
            Text(
                "This build has no pinned release certificate -- it will refuse to install any " +
                    "update at all until built by the AI-approved CI release pipeline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(
            enabled = !checking,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                checking = true
                statusMessage = "Checking..."
                coroutineScope.launch {
                    when (val result = updateManager.checkForUpdate()) {
                        is UpdateCheckResult.UpToDate -> statusMessage = "Already up to date."
                        is UpdateCheckResult.UpdateAvailable -> {
                            // Enqueue the same background worker the daily periodic check uses,
                            // rather than blocking this screen on the full download/verify/install
                            // -- UpdateInstallResultReceiver posts a notification once it's done.
                            UpdateCheckWorker.enqueueOneShot(context)
                            statusMessage = "Update available: ${result.manifest.versionName} -- " +
                                "installing in the background. You'll get a notification when it's done."
                        }
                        is UpdateCheckResult.Error -> statusMessage = "Check failed: ${result.message}"
                    }
                    checking = false
                }
            },
        ) {
            Text("Check for update")
        }
        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        Text("Request an update", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Opens a browser to file a GitHub issue asking for a review/release, and can alert " +
                "the Guardian SMS contact. Doesn't install anything by itself -- someone still " +
                "has to make the change and get an AI VERDICT: PASS so CI can sign and publish.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = issuesUrl,
            onValueChange = { issuesUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("GitHub \"new issue\" URL") },
            singleLine = true,
            placeholder = { Text("https://github.com/you/otterling/issues/new") },
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { updateSettings.setGithubIssuesUrl(issuesUrl) },
        ) {
            Text("Save")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                coroutineScope.launch {
                    runCatching {
                        AlertReporter(context).report(
                            type = "UPDATE_REQUESTED",
                            details = "Update requested from the Otterling app",
                            severity = AlertSeverity.INFO,
                        )
                    }
                    val url = updateSettings.githubIssuesUrl()
                    if (url.isNotBlank()) {
                        val uri = Uri.parse(url).buildUpon()
                            .appendQueryParameter("title", "Update requested from Otterling app")
                            .appendQueryParameter(
                                "body",
                                "Requesting review of a change and a new AI-approved release.",
                            )
                            .build()
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        requestStatusMessage = "Opened GitHub and sent an alert if SMS is configured."
                    } else {
                        requestStatusMessage = "Alert sent if SMS is configured (no GitHub URL above)."
                    }
                }
            },
        ) {
            Text("Request update")
        }
        if (requestStatusMessage.isNotEmpty()) {
            Text(requestStatusMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}
