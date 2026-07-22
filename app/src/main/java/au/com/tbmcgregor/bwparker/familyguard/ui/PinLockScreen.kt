package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import au.com.tbmcgregor.bwparker.familyguard.pin.GuardianKeyManager
import au.com.tbmcgregor.bwparker.familyguard.pin.GuardianSetupClient
import au.com.tbmcgregor.bwparker.familyguard.pin.PinAuthManager
import kotlinx.coroutines.launch

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/**
 * Gate shown before Settings. If no PIN has been set yet, walks through a create-and-confirm
 * flow first; otherwise just prompts for the existing PIN.
 */
@Composable
fun PinLockScreen(
    pinAuthManager: PinAuthManager,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    val hasPin = remember { pinAuthManager.hasPin() }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    fun submit() {
        if (hasPin) {
            if (pinAuthManager.verifyPin(pin)) {
                onUnlocked()
            } else {
                errorMessage = "Incorrect PIN"
                pin = ""
            }
        } else {
            when {
                pin.length < MIN_PIN_LENGTH -> errorMessage = "PIN must be at least $MIN_PIN_LENGTH digits"
                pin != confirmPin -> errorMessage = "PINs don't match"
                else -> {
                    pinAuthManager.setPin(pin)
                    onUnlocked()
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (hasPin) "Enter PIN" else "Create a PIN",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            if (hasPin) {
                "Settings are locked behind this PIN."
            } else {
                "This PIN will be required to reach Settings from now on."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= MAX_PIN_LENGTH) pin = it.filter(Char::isDigit) },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!hasPin) {
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= MAX_PIN_LENGTH) confirmPin = it.filter(Char::isDigit) },
                label = { Text("Confirm PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (errorMessage.isNotEmpty()) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::submit) {
                Text(if (hasPin) "Unlock" else "Set PIN")
            }
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }

        if (!hasPin) {
            GuardianClaimSection(pinAuthManager = pinAuthManager, onClaimed = onUnlocked)
        }
    }
}

/**
 * Lets someone else (a Guardian) set this PIN remotely instead of you typing it in yourself, via
 * the one-time link flow in `server/guardian_relay.py`. Two things are needed here: this phone's
 * public key (to hand to whoever builds the link, so the Guardian's browser can encrypt against
 * it) and, once the Guardian has submitted the link, a way to fetch and apply the result.
 */
@Composable
private fun GuardianClaimSection(pinAuthManager: PinAuthManager, onClaimed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var setupLink by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var claiming by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide Guardian setup" else "Set up via a Guardian link instead")
    }

    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Copy this phone's public key and send it to whoever is building your setup link:",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { copyToClipboard(context, GuardianKeyManager.publicKeyBase64()) }) {
                Text("Copy this phone's public key")
            }

            Text(
                "Once they've sent you back the setup link (after the Guardian submits it), paste it here:",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = setupLink,
                onValueChange = { setupLink = it },
                label = { Text("Setup link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !claiming,
                onClick = {
                    val parsed = parseSetupLink(setupLink)
                    if (parsed == null) {
                        status = "Couldn't read a token out of that link."
                        return@Button
                    }
                    claiming = true
                    status = "Claiming..."
                    scope.launch {
                        val result = GuardianSetupClient.claimPin(parsed.first, parsed.second, pinAuthManager)
                        claiming = false
                        when (result) {
                            is GuardianSetupClient.ClaimResult.Success -> onClaimed()
                            is GuardianSetupClient.ClaimResult.Failure -> status = result.message
                        }
                    }
                },
            ) {
                Text("Claim PIN from link")
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Guardian public key", text))
}

/** Pulls `(serverBaseUrl, token)` out of a `.../setup/<token>?...` link, or null if malformed. */
private fun parseSetupLink(link: String): Pair<String, String>? {
    val uri = Uri.parse(link.trim())
    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    val segments = uri.pathSegments
    val setupIndex = segments.indexOf("setup")
    if (setupIndex == -1 || setupIndex + 1 >= segments.size) return null
    val token = segments[setupIndex + 1]
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$port" to token
}
