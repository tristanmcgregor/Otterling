package app.otterling.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
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
import app.otterling.alerts.AccountabilityPartnerSettings
import app.otterling.alerts.AlertReporter
import app.otterling.alerts.GuardianAlertSettings
import app.otterling.alerts.SmsPermissionGranter
import kotlinx.coroutines.launch

/**
 * A second, independent SMS recipient for the same real-time alerts the Guardian gets (see
 * [GuardianSmsAlertsSection]) -- this section only configures *who else* is told; what counts as
 * a flagged event (trigger words, watched apps) stays Guardian-owned/shared, so this section
 * deliberately doesn't duplicate that UI.
 */
@Composable
fun AccountabilityPartnerSection(context: Context) {
    val settings = remember { AccountabilityPartnerSettings(context) }
    val reporter = remember { AlertReporter(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        enabled = settings.isEnabled()
        number = settings.partnerNumber()
        SmsPermissionGranter.grantSendSms(context)
    }

    SectionCard(
        title = "Accountability partner SMS alerts",
        icon = Icons.Default.Groups,
        subtitle = "Text a separate accountability partner the same real-time alerts the " +
            "guardian gets (tamper, watched apps, trigger words, blocked sites) -- its own " +
            "independent daily cap, so this doesn't affect the guardian's alerts.",
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
            label = { Text("Accountability partner phone number") },
            singleLine = true,
            placeholder = { Text("+614...") },
        )
        Button(
            onClick = {
                settings.setPartnerNumber(number)
                status = "Number saved"
                refresh++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save number")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    SmsPermissionGranter.grantSendSms(context)
                    settings.setPartnerNumber(number)
                    val ok = reporter.sendTestSmsToPartner()
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
            "Today: ${settings.dailySentCount()} / ${GuardianAlertSettings.DAILY_SMS_CAP} SMS (independent of the guardian's cap)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
