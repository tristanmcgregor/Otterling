package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import au.com.tbmcgregor.bwparker.familyguard.BuildConfig
import au.com.tbmcgregor.bwparker.familyguard.alerts.AlertReporter
import au.com.tbmcgregor.bwparker.familyguard.alerts.AlertSeverity
import au.com.tbmcgregor.bwparker.familyguard.updates.ApprovedUpdateManager
import au.com.tbmcgregor.bwparker.familyguard.updates.InstallResult
import au.com.tbmcgregor.bwparker.familyguard.updates.UpdateCheckResult
import au.com.tbmcgregor.bwparker.familyguard.updates.UpdateManifest
import au.com.tbmcgregor.bwparker.familyguard.updates.UpdateSettings
import kotlinx.coroutines.launch

@Composable
fun UpdateSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val updateManager = remember { ApprovedUpdateManager(context) }
    val updateSettings = remember { UpdateSettings(context) }

    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var availableManifest by remember { mutableStateOf<UpdateManifest?>(null) }
    var issuesUrl by remember { mutableStateOf(updateSettings.githubIssuesUrl()) }
    var requestStatusMessage by remember { mutableStateOf("") }

    SectionCard(
        title = "App updates",
        icon = Icons.Default.SystemUpdate,
        subtitle = "The only way this app updates itself: a signed build that passed AI review " +
            "against scripts/update_review_checklist.md and was approved by the Guardian in a " +
            "protected GitHub environment, published to the family's own update server. There is " +
            "no way to install a locally-built or otherwise-unsigned APK from this screen -- " +
            "that would defeat the VPN lockdown, proxy fail-closed behavior, and every other " +
            "protection in this app.",
    ) {
        Text("Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
        if (BuildConfig.RELEASE_CERT_SHA256.isBlank()) {
            Text(
                "This build has no pinned release certificate -- it will refuse to install any " +
                    "update at all until built by the Guardian-approved CI release environment.",
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
                availableManifest = null
                coroutineScope.launch {
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
            },
        ) {
            Text("Check for update")
        }

        availableManifest?.let { manifest ->
            Button(
                enabled = !installing,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    installing = true
                    statusMessage = "Downloading and verifying..."
                    coroutineScope.launch {
                        val result = updateManager.downloadVerifyAndInstall(manifest)
                        statusMessage = when (result) {
                            is InstallResult.Started -> "Verified -- installing now."
                            is InstallResult.Rejected -> "Rejected: ${result.reason}"
                        }
                        installing = false
                    }
                },
            ) {
                Text("Install verified update (${manifest.versionName})")
            }
        }
        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        Text("Request an update", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Opens a browser to file a GitHub issue asking for a review/release, and alerts the " +
                "Guardian. Doesn't install anything by itself -- someone still has to make the " +
                "change, pass AI review, and get Guardian approval.",
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
                                "Requesting review of a change and a new Guardian-approved release.",
                            )
                            .build()
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        requestStatusMessage = "Opened GitHub and alerted the Guardian."
                    } else {
                        requestStatusMessage = "Alerted the Guardian (no GitHub URL configured above)."
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
