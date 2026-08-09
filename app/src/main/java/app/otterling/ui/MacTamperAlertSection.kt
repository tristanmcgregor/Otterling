package app.otterling.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.alerts.MacTamperPollWorker
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings for polling the macOS app's filter-server for tamper events (lock profile removed,
 * filter daemon unloaded, etc. -- see `filter-server/lockprofile_service.py` and
 * [MacTamperPollWorker]) and feeding them into this phone's existing SMS pipeline
 * ([app.otterling.alerts.AlertReporter]/[app.otterling.alerts.AccountabilityPartnerSection]) --
 * same recipients/caps as every other accountability alert, just a second source feeding it.
 * Only the token needs entering here; the server host is shared with the cloud content filter
 * (see [app.otterling.content.CloudFilterSettings], configured under VPN filter settings).
 */
@Composable
fun MacTamperAlertSection(context: Context) {
    val settings = remember { MacTamperPollSettings(context) }
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(false) }
    var polling by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var lastPolledText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        token = settings.token()
        hasToken = settings.isConfigured()
    }

    fun refreshLastPolledText() {
        val millis = settings.lastPolledAtMillis()
        lastPolledText = if (millis > 0) {
            "Last checked: ${DateFormat.getDateTimeInstance().format(Date(millis))}"
        } else {
            "Never checked yet"
        }
    }
    LaunchedEffect(Unit) { refreshLastPolledText() }

    SectionCard(
        title = "Mac tamper alerts",
        icon = Icons.Default.LaptopMac,
        subtitle = "Checks the family's filter-server (LOCKPROFILE_TOKEN in its .env) every " +
            "15 minutes for tamper events reported by the macOS app -- lock profile removed, " +
            "filter daemon unloaded and recovered, etc. -- and texts this phone's existing " +
            "accountability partners the same way any other alert here does. Blank token = " +
            "disabled, nothing polled.",
    ) {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mac LOCKPROFILE_TOKEN") },
            singleLine = true,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                settings.setToken(token)
                hasToken = settings.isConfigured()
                status = if (token.isBlank()) "Saved -- polling disabled (no token)." else "Saved."
            },
        ) {
            Text("Save")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !polling && hasToken,
            onClick = {
                polling = true
                status = "Checking..."
                MacTamperPollWorker.enqueueOneShot(context)
                scope.launch {
                    // enqueueOneShot() only schedules the work; there's no direct completion
                    // callback wired up here, so this just gives the worker a moment to run and
                    // then re-reads the persisted "last checked" timestamp it updates on success --
                    // consistent with this screen's otherwise-simple poll-settings style rather
                    // than adding WorkManager LiveData observation for one button.
                    delay(4_000)
                    refreshLastPolledText()
                    status = "Checked."
                    polling = false
                }
            },
        ) {
            Text("Check now")
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Text(lastPolledText, style = MaterialTheme.typography.bodySmall)
    }
}
