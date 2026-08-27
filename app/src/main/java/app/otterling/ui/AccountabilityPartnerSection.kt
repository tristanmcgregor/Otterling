package app.otterling.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import app.otterling.alerts.AccountabilityPartnerSettings
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.GuardianAlertSettings
import app.otterling.alerts.GuardianSmsSender
import app.otterling.alerts.SmsPermissionGranter
import app.otterling.tamper.TamperEventLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single, complete SMS-alert settings section: who gets texted (any number of accountability
 * partners, each with its own independent daily cap -- [AccountabilityPartnerSettings]) and what
 * counts as a flagged event (trigger words, watched apps, info-level opt-in --
 * [GuardianAlertSettings], kept under that name only to avoid resetting already-configured
 * detection settings). Replaces the old separate Guardian SMS section entirely -- there's no
 * separate Guardian recipient anymore, just this list.
 */
@Composable
fun AccountabilityPartnerSection(context: Context) {
    val settings = remember { AccountabilityPartnerSettings(context) }
    val detectionSettings = remember { GuardianAlertSettings(context) }
    val reporter = remember { AlertReporter(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var numbers by remember { mutableStateOf<List<String>>(emptyList()) }
    var newNumber by remember { mutableStateOf("") }
    var triggers by remember { mutableStateOf("") }
    var dashboardTriggers by remember { mutableStateOf<List<String>>(emptyList()) }
    var smsInfo by remember { mutableStateOf(false) }
    var watched by remember { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var showTriggerWordsDialog by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }

    LaunchedEffect(refresh) {
        enabled = settings.isEnabled()
        numbers = settings.partnerNumbers()
        triggers = detectionSettings.triggerWords().joinToString("\n")
        dashboardTriggers = detectionSettings.dashboardTriggerWords()
        smsInfo = detectionSettings.smsInfoEvents()
        watched = detectionSettings.watchedPackages()
        SmsPermissionGranter.grantSendSms(context)
    }

    if (showPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showPicker = false },
            onSelect = { app ->
                detectionSettings.addWatchedPackage(app.packageName)
                showPicker = false
                refresh++
            },
        )
    }

    // Kept behind an explicit dialog, not inline in the main list, so the trigger-word list isn't
    // sitting in plain sight the moment someone opens Settings.
    if (showTriggerWordsDialog) {
        var draft by remember { mutableStateOf(detectionSettings.localTriggerWords().joinToString("\n")) }
        AlertDialog(
            onDismissRequest = { showTriggerWordsDialog = false },
            title = { Text("Trigger words") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("One per line") },
                        minLines = 5,
                    )
                    if (dashboardTriggers.isNotEmpty()) {
                        Text(
                            "Also managed by dashboard: ${dashboardTriggers.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    detectionSettings.setTriggerWords(draft)
                    triggers = detectionSettings.triggerWords().joinToString("\n")
                    status = "Trigger words saved"
                    showTriggerWordsDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTriggerWordsDialog = false }) { Text("Cancel") }
            },
        )
    }

    SectionCard(
        title = "Accountability partner SMS alerts",
        icon = Icons.Default.Groups,
        subtitle = "Text one or more accountability partners when something needs attention -- " +
            "tamper, watched apps, trigger words, blocked sites. Uses this phone's SIM; Device " +
            "Owner locks SEND_SMS.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SMS alerts enabled", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = enabled,
                onCheckedChange = { newValue ->
                    if (!newValue && enabled) {
                        // Notify BEFORE persisting the disable -- once settings.isEnabled() is
                        // false, the normal AlertReporter path would correctly refuse to send
                        // anything, so this is the only way any of these numbers ever hear about
                        // their own alerting being turned off.
                        val goingSilentNumbers = settings.partnerNumbers()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val sender = GuardianSmsSender(context)
                                goingSilentNumbers.forEach { number ->
                                    sender.send(
                                        "Otterling: accountability partner alerts were just turned off on this device.",
                                        number,
                                    )
                                }
                            }
                            runCatching {
                                TamperEventLogger(context).log(
                                    type = "ACCOUNTABILITY_ALERTS_DISABLED",
                                    details = "SMS alerts turned off (was texting ${goingSilentNumbers.size} number(s))",
                                )
                            }
                        }
                    }
                    settings.setEnabled(newValue)
                    enabled = newValue
                    SmsPermissionGranter.grantSendSms(context)
                },
            )
        }

        Text("Accountability partners", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = newNumber,
            onValueChange = { newNumber = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add phone number") },
            singleLine = true,
            placeholder = { Text("+614...") },
        )
        Button(
            onClick = {
                val trimmed = newNumber.trim()
                val alreadyKnown = trimmed in settings.partnerNumbers()
                settings.addPartnerNumber(newNumber)
                newNumber = ""
                status = "Number added"
                refresh++
                // Only for a genuinely new number -- re-adding one already in the list (a no-op in
                // AccountabilityPartnerSettings.addPartnerNumber) must not re-send the welcome
                // text, since this button offers no other way to resend it deliberately.
                if (!alreadyKnown && trimmed.isNotEmpty()) {
                    scope.launch {
                        reporter.sendWelcomeMessage(trimmed)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = newNumber.isNotBlank(),
        ) {
            Text("Add number")
        }
        numbers.forEach { num ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$num — ${settings.dailySentCount(num)}/${AccountabilityPartnerSettings.DAILY_SMS_CAP} today",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = {
                    // Same reasoning as disabling the master switch -- notify this specific
                    // number before it's removed, since it's the last chance to reach it.
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            GuardianSmsSender(context).send(
                                "Otterling: you've been removed from accountability alerts on this device.",
                                num,
                            )
                        }
                        runCatching {
                            TamperEventLogger(context).log(
                                type = "ACCOUNTABILITY_PARTNER_REMOVED",
                                details = "Removed $num",
                                debounceKey = "ACCOUNTABILITY_PARTNER_REMOVED|$num",
                            )
                        }
                    }
                    settings.removePartnerNumber(num)
                    refresh++
                }) {
                    Text("Remove")
                }
            }
        }

        val triggerWordCount = triggers.lines().count { it.isNotBlank() }
        OutlinedButton(
            onClick = { showTriggerWordsDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (triggerWordCount > 0) {
                    "Trigger words ($triggerWordCount configured)"
                } else {
                    "Trigger words (none configured)"
                },
            )
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
                    detectionSettings.setSmsInfoEvents(it)
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
                    detectionSettings.removeWatchedPackage(pkg)
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
                    val count = reporter.sendTestSmsToPartner()
                    status = if (count > 0) {
                        "Test SMS sent to $count partner(s)"
                    } else {
                        "Test SMS failed (check numbers / SIM / permission)"
                    }
                    refresh++
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = numbers.isNotEmpty(),
        ) {
            Text("Send test SMS")
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
