package au.com.tbmcgregor.bwparker.familyguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.com.tbmcgregor.bwparker.familyguard.pin.PinAuthManager

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/**
 * Gate shown before Settings, rebuilt as a numeric keypad. If no PIN has been set yet, walks
 * through a create-and-confirm flow first; otherwise just prompts for the existing PIN. All PIN
 * validation, lockout, and persistence stays in [PinAuthManager] exactly as before.
 */
@Composable
fun PinLockScreen(
    pinAuthManager: PinAuthManager,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    val hasPin = remember { pinAuthManager.hasPin() }
    var pin by remember { mutableStateOf("") }
    var firstEntry by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
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
        } else if (!confirming) {
            if (pin.length < MIN_PIN_LENGTH) {
                errorMessage = "PIN must be at least $MIN_PIN_LENGTH digits"
            } else {
                firstEntry = pin
                pin = ""
                confirming = true
                errorMessage = ""
            }
        } else {
            if (pin != firstEntry) {
                errorMessage = "PINs don't match"
                firstEntry = ""
                pin = ""
                confirming = false
            } else {
                pinAuthManager.setPin(firstEntry)
                onUnlocked()
            }
        }
    }

    fun press(digit: String) {
        if (pin.length < MAX_PIN_LENGTH) {
            pin += digit
            errorMessage = ""
        }
    }

    val title = when {
        hasPin -> "Guardian Settings"
        confirming -> "Confirm your PIN"
        else -> "Create a PIN"
    }
    val subtitle = when {
        hasPin -> "Enter your PIN to continue"
        confirming -> "Re-enter the PIN to confirm"
        else -> "Choose a $MIN_PIN_LENGTH–$MAX_PIN_LENGTH digit PIN"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier.padding(8.dp).align(Alignment.TopStart),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))
            PinDots(count = maxOf(MIN_PIN_LENGTH, pin.length), filled = pin.length, error = errorMessage.isNotEmpty())
            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                if (errorMessage.isNotEmpty()) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Keypad(
                onDigit = ::press,
                onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                onSubmit = ::submit,
                submitEnabled = pin.length >= MIN_PIN_LENGTH,
            )
        }
    }
}

@Composable
private fun PinDots(count: Int, filled: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(count) { i ->
            val color = when {
                error -> MaterialTheme.colorScheme.error
                i < filled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { digit ->
                    KeyButton(onClick = { onDigit(digit) }) {
                        Text(digit, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeyButton(onClick = onBackspace, filled = false) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            KeyButton(onClick = { onDigit("0") }) {
                Text("0", style = MaterialTheme.typography.headlineSmall)
            }
            KeyButton(
                onClick = { if (submitEnabled) onSubmit() },
                filled = false,
                accent = submitEnabled,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Confirm",
                    tint = if (submitEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyButton(
    onClick: () -> Unit,
    filled: Boolean = true,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val bg = when {
        accent -> MaterialTheme.colorScheme.primaryContainer
        filled -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.background
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(68.dp),
        shape = CircleShape,
        color = bg,
        shadowElevation = if (filled) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
