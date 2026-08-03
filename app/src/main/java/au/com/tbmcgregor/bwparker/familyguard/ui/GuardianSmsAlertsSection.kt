package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import au.com.tbmcgregor.bwparker.familyguard.alerts.AlertReporter
import au.com.tbmcgregor.bwparker.familyguard.alerts.GuardianAlertSettings
import au.com.tbmcgregor.bwparker.familyguard.alerts.SmsPermissionGranter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GuardianSmsAlertsSection(context: Context) {
    val settings = remember { GuardianAlertSettings(context) }
    val reporter = remember { AlertReporter(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var triggers by remember { mutableStateOf("") }
    var smsInfo by remember { mutableStateOf(false) }
    var watched by remember { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }

    LaunchedEffect(refresh) {
        enabled = settings.isEnabled()
        number = settings.guardianNumber()
        triggers = settings.triggerWords().joinToString("\n")
        smsInfo = settings.smsInfoEvents()
        watched = settings.watchedPackages()
        SmsPermissionGranter.grantSendSms(context)
    }

    if (showPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showPicker = false },
            onSelect = { app ->
                settings.addWatchedPackage(app.packageName)
                showPicker = false
                refresh++
            },
        )
    }

    SectionCard(
        title = "Guardian SMS alerts",
        icon = Icons.Default.Sms,
        subtitle = "Text a guardian when something suspicious happens (tamper, watched apps, " +
            "trigger words, blocked sites). Uses this phone's SIM; Device Owner locks SEND_SMS.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SMS alerts enabled", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    settings.setEnabled(it)
                    enabled = it
                    SmsPermissionGranter.grantSendSms(context)
                },
            )
        }

        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Guardian phone number") },
            singleLine = true,
            placeholder = { Text("+614...") },
        )
        Button(
            onClick = {
                settings.setGuardianNumber(number)
                status = "Number saved"
                refresh++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save number")
        }

        OutlinedTextField(
            value = triggers,
            onValueChange = { triggers = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Trigger words (one per line)") },
            minLines = 3,
        )
        Button(
            onClick = {
                settings.setTriggerWords(triggers)
                status = "Trigger words saved"
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save trigger words")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SMS for info-level events too", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = smsInfo,
                onCheckedChange = {
                    settings.setSmsInfoEvents(it)
                    smsInfo = it
                },
            )
        }

        Text("Watched apps", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Opening these apps sends an SMS alert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                scope.launch {
                    installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                    showPicker = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add watched app")
        }
        watched.forEach { pkg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pkg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    settings.removeWatchedPackage(pkg)
                    refresh++
                }) {
                    Text("Remove")
                }
            }
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    SmsPermissionGranter.grantSendSms(context)
                    settings.setGuardianNumber(number)
                    val ok = reporter.sendTestSms()
                    status = if (ok) {
                        "Test SMS sent"
                    } else {
                        "Test SMS failed (check number / SIM / permission)"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = number.isNotBlank(),
        ) {
            Text("Send test SMS")
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Today: ${settings.dailySentCount()} / ${GuardianAlertSettings.DAILY_SMS_CAP} SMS",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
