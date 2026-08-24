package app.otterling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.otterling.pin.PinAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/**
 * Gate shown before Settings, a numeric keypad. The Guardian PIN is set exclusively from the
 * guardian dashboard and verified live against the server on every attempt (see
 * [PinAuthManager.verify] -- the PIN itself never reaches this device, only correct/incorrect
 * answers to one guess at a time, under server-side lockout). This screen never lets anyone
 * create, change, or clear a PIN locally, so the one 4-digit PIN set on the website is the only
 * one that ever exists anywhere in the Otterling ecosystem.
 *
 * [NoPinYetScreen] is shown instead of the keypad only when [PinAuthManager.cachedHasPin] is
 * confirmed false by a real server response -- its default (`true`) until that first successful
 * fetch means a fresh install or a device that hasn't synced yet gates Settings shut, not open.
 */
@Composable
fun PinLockScreen(
    pinAuthManager: PinAuthManager,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
) {
    val hasPin = remember { pinAuthManager.cachedHasPin() }
    if (!hasPin) {
        NoPinYetScreen(onContinue = onUnlocked, onCancel = onCancel)
        return
    }

    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }

    fun submit() {
        if (verifying) return
        verifying = true
        errorMessage = ""
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { pinAuthManager.verify(pin) }
            verifying = false
            when (outcome.result) {
                // A genuine server-confirmed "no PIN configured" is trusted the same as every
                // other dashboard-driven config in this app -- the guardian removed protection
                // deliberately, not something this screen decides on its own.
                PinAuthManager.VerifyResult.CORRECT, PinAuthManager.VerifyResult.NO_PIN_SET -> onUnlocked()
                PinAuthManager.VerifyResult.INCORRECT -> {
                    errorMessage = "Incorrect PIN"
                    pin = ""
                }
                PinAuthManager.VerifyResult.LOCKED_OUT -> {
                    errorMessage = "Too many wrong PINs -- try again in ${(outcome.retryAfterMs / 1000) + 1}s"
                    pin = ""
                }
                PinAuthManager.VerifyResult.NETWORK_ERROR -> {
                    errorMessage = "Can't verify PIN right now -- check your connection"
                    pin = ""
                }
            }
        }
    }

    fun press(digit: String) {
        if (pin.length < MAX_PIN_LENGTH) {
            pin += digit
            errorMessage = ""
        }
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
            Text("Guardian Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Enter the Guardian PIN to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))
            PinDots(count = maxOf(MIN_PIN_LENGTH, pin.length), filled = pin.length, error = errorMessage.isNotEmpty())
            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                if (verifying) {
                    Text(
                        "Verifying...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                } else if (errorMessage.isNotEmpty()) {
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
                submitEnabled = pin.length >= MIN_PIN_LENGTH && !verifying,
            )
        }
    }
}

/** Shown in place of the keypad when [PinAuthManager.cachedHasPin] is false -- see
 *  [PinLockScreen]'s doc comment for why there's no local create-a-PIN fallback anymore. */
@Composable
private fun NoPinYetScreen(onContinue: () -> Unit, onCancel: () -> Unit) {
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
            Text("No Guardian PIN set yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "The Guardian PIN is set from the dashboard, not on this phone. Once your " +
                    "guardian sets one there, it syncs here automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onContinue) {
                Text("Continue")
            }
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
