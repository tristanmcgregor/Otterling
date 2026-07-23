package au.com.tbmcgregor.bwparker.familyguard.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import au.com.tbmcgregor.bwparker.familyguard.pin.PinAuthManager

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/**
 * Gate shown before Settings. If no PIN has been set yet, walks through a create-and-confirm
 * flow first; otherwise just prompts for the existing PIN. The PIN is set manually here -- a
 * Guardian sets it by typing it in on the device directly.
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
            val lockoutMs = pinAuthManager.lockoutRemainingMillis()
            if (lockoutMs > 0) {
                errorMessage = "Too many wrong PINs -- try again in ${(lockoutMs / 1000) + 1}s"
                pin = ""
            } else if (pinAuthManager.verifyPin(pin)) {
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
    }
}
