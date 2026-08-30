package app.otterling.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.otterling.admin.DeviceOwnerManager
import app.otterling.alerts.AccountabilityPartnerSettings
import app.otterling.alerts.SmsPermissionGranter
import app.otterling.content.CloudFilterSettings
import app.otterling.content.DomainBlocklistManager
import app.otterling.content.VpnFilterManager
import app.otterling.onboarding.OnboardingState
import app.otterling.onboarding.OnboardingStep
import app.otterling.onboarding.resolveOnboardingStep
import app.otterling.restrictions.AccessibilityGuard
import app.otterling.restrictions.DeviceRestrictionsManager
import app.otterling.restrictions.OverlayPermissionManager
import app.otterling.restrictions.Restriction
import app.otterling.ui.components.FilledPillButton
import app.otterling.ui.components.OutlinedPillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup wizard: a hard gate shown on every cold start until every step below is
 * satisfied, resuming at whichever is first-incomplete (see [resolveOnboardingStep]) rather than
 * always restarting from the top. Once [OnboardingState.markComplete] is called (from [DoneStep]),
 * this never runs again -- it's strictly a first-run gate, not an ongoing enforcement mechanism.
 *
 * Deliberately has no `BackHandler`: the host Activity is `singleTop` with no back stack, so
 * system Back/Home just backgrounds the app, and reopening re-resolves the correct resume step
 * live. There's nothing to trap the user inside.
 */
@Composable
fun OnboardingWizard(context: Context, onComplete: () -> Unit) {
    val onboardingState = remember { OnboardingState(context) }
    var currentStep by remember {
        mutableStateOf(
            if (!onboardingState.hasSeenWelcome()) OnboardingStep.Welcome else resolveOnboardingStep(context),
        )
    }

    when (currentStep) {
        OnboardingStep.Welcome -> WelcomeStep(
            onContinue = {
                onboardingState.setSeenWelcome()
                currentStep = resolveOnboardingStep(context)
            },
        )
        OnboardingStep.DeviceOwner -> DeviceOwnerStep(context, onContinue = { currentStep = OnboardingStep.Restrictions })
        OnboardingStep.Restrictions -> RestrictionsStep(context, onContinue = { currentStep = OnboardingStep.ContentFilter })
        OnboardingStep.ContentFilter -> ContentFilterStep(context, onContinue = { currentStep = OnboardingStep.AccountabilityPartnerSms })
        OnboardingStep.AccountabilityPartnerSms -> AccountabilityPartnerSmsStep(context, onContinue = { currentStep = OnboardingStep.Accessibility })
        OnboardingStep.Accessibility -> AccessibilityStep(context, onContinue = { currentStep = OnboardingStep.Overlay })
        OnboardingStep.Overlay -> OverlayStep(context, onContinue = { currentStep = OnboardingStep.Done })
        OnboardingStep.Done -> DoneStep(
            onFinish = {
                onboardingState.markComplete()
                onComplete()
            },
        )
    }
}

/**
 * Shared full-screen layout for every wizard step. [onContinue] being `null` is the hard-block
 * mechanism used throughout this wizard: no button is rendered at all until a step's own live
 * check passes, rather than showing a disabled button -- there is nothing to tap yet.
 */
@Composable
private fun WizardScaffold(
    title: String,
    subtitle: String,
    onContinue: (() -> Unit)?,
    continueLabel: String = "Continue",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        Spacer(Modifier.height(8.dp))
        if (onContinue != null) {
            FilledPillButton(text = continueLabel, onClick = onContinue, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WizardCodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    WizardScaffold(
        title = "Welcome to Otterling",
        subtitle = "Let's get this device set up together. You'll go through a few steps: " +
            "confirming Device Owner, and turning on protections one at a time. Everything here " +
            "can be fine-tuned later in Settings. (The Guardian PIN is set separately, from the " +
            "dashboard.)",
        onContinue = onContinue,
        continueLabel = "Get started",
    ) {}
}

@Composable
private fun DeviceOwnerStep(context: Context, onContinue: () -> Unit) {
    val ownerManager = remember { DeviceOwnerManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val status = remember(refreshTrigger) { ownerManager.currentStatus() }

    WizardScaffold(
        title = "Confirm Device Owner",
        subtitle = "Otterling needs to be this device's Device Owner before anything else can be " +
            "configured. This is a one-time step done from a computer, before any Google account " +
            "is signed in on this device.",
        onContinue = if (status.isDeviceOwner) onContinue else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Device owner", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (status.isDeviceOwner) "Active" else "Not active", isGood = status.isDeviceOwner)
        }
        if (!status.isDeviceOwner) {
            Text(
                "Run this from a computer with adb, phone connected via USB:",
                style = MaterialTheme.typography.bodySmall,
            )
            WizardCodeBlock(ownerManager.provisioningAdbCommand)
        }
        OutlinedPillButton(text = "Check again", onClick = { refreshTrigger++ })
    }
}

@Composable
private fun RestrictionsStep(context: Context, onContinue: () -> Unit) {
    val restrictionsManager = remember { DeviceRestrictionsManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val satisfied = remember(refreshTrigger) {
        Restriction.entries.all { restrictionsManager.isEnabled(it) } && restrictionsManager.isUninstallBlocked()
    }

    WizardScaffold(
        title = "Tamper resistance",
        subtitle = "Blocks Safe Mode boot, factory reset, USB debugging, and additional user " +
            "accounts, and stops Otterling itself from being uninstalled. Standard Android " +
            "Device Owner restrictions -- reversible only by the Guardian.",
        onContinue = if (satisfied) onContinue else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Restrictions", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (satisfied) "Enabled" else "Not enabled", isGood = satisfied)
        }
        if (!satisfied) {
            FilledPillButton(
                text = "Enable recommended protections",
                onClick = {
                    restrictionsManager.applyDefaults()
                    refreshTrigger++
                },
            )
        }
    }
}

@Composable
private fun ContentFilterStep(context: Context, onContinue: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val vpnManager = remember { VpnFilterManager(context) }
    val cloudFilterSettings = remember { CloudFilterSettings(context) }
    val blocklistManager = remember { DomainBlocklistManager(context) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(vpnManager.wasEnabledByUser()) }

    WizardScaffold(
        title = "Content filter",
        subtitle = "Filters adult content via a local blocklist plus your filter server " +
            "(${cloudFilterSettings.host()}), and locks the device's network down so nothing " +
            "else can bypass it. You can fine-tune the server, its port, and proxy details later " +
            "in Settings.",
        onContinue = if (enabled) onContinue else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Content filter", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (enabled) "Enabled" else "Disabled", isGood = enabled)
        }
        if (!enabled) {
            FilledPillButton(
                text = "Enable content filter",
                enabled = !busy,
                onClick = {
                    busy = true
                    statusMessage = "Downloading blocklist..."
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) { blocklistManager.refresh() }
                        // The VPN toggle alone only turns on the local blocklist + last-resort
                        // fallback DNS -- this also opts into the cloud DNS filter and (since its
                        // own proxy sub-toggle defaults to true once this is on) the filter proxy,
                        // so "content filter" here means full protection, not just the VPN shell.
                        cloudFilterSettings.setEnabled(true)
                        val didEnable = withContext(Dispatchers.IO) { vpnManager.enable() }
                        enabled = didEnable
                        statusMessage = if (didEnable) "Enabled." else "Failed -- is Device Owner active?"
                        busy = false
                    }
                },
            )
        }
        if (statusMessage.isNotEmpty()) {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AccountabilityPartnerSmsStep(context: Context, onContinue: () -> Unit) {
    val settings = remember { AccountabilityPartnerSettings(context) }
    var number by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val satisfied = remember(refreshTrigger) { settings.isEnabled() && settings.partnerNumbers().isNotEmpty() }

    WizardScaffold(
        title = "Accountability partner alerts",
        subtitle = "Otterling can text an accountability partner's phone when something needs " +
            "attention -- a blocked site, a tamper attempt, or similar. Uses this phone's own " +
            "SIM. You can add more partners later in settings.",
        onContinue = if (satisfied) onContinue else null,
    ) {
        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Accountability partner phone number") },
            singleLine = true,
            placeholder = { Text("+614...") },
        )
        FilledPillButton(
            text = "Save",
            enabled = number.isNotBlank(),
            onClick = {
                settings.addPartnerNumber(number)
                settings.setEnabled(true)
                SmsPermissionGranter.grantSendSms(context)
                number = ""
                refreshTrigger++
            },
        )
    }
}

@Composable
private fun AccessibilityStep(context: Context, onContinue: () -> Unit) {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val enabled = remember(refreshTrigger) { AccessibilityGuard.isEnabled(context) }

    WizardScaffold(
        title = "Accessibility service",
        subtitle = "Powers tamper detection and enforcement. Android has no Device Owner API to " +
            "turn this on for you -- it has to be enabled manually, once, in system Settings.",
        onContinue = if (enabled) onContinue else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Accessibility service", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (enabled) "Enabled" else "Not enabled", isGood = enabled)
        }
        FilledPillButton(
            text = "Open Accessibility settings",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        OutlinedPillButton(text = "Refresh status", onClick = { refreshTrigger++ })
    }
}

@Composable
private fun OverlayStep(context: Context, onContinue: () -> Unit) {
    val overlayManager = remember { OverlayPermissionManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val granted = remember(refreshTrigger) { overlayManager.isGranted() }

    WizardScaffold(
        title = "Appear on top",
        subtitle = "Lets Otterling show a brief on-screen warning the moment it flags a " +
            "screenshot as NSFW, before the app is actually blocked. Requires \"Display over " +
            "other apps\" permission, granted once in system Settings.",
        onContinue = if (granted) onContinue else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Appear on top", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (granted) "Granted" else "Not granted", isGood = granted)
        }
        FilledPillButton(
            text = "Open permission settings",
            onClick = { context.startActivity(overlayManager.permissionRequestIntent()) },
        )
        OutlinedPillButton(text = "Refresh status", onClick = { refreshTrigger++ })
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    WizardScaffold(
        title = "You're all set",
        subtitle = "Device Owner is active, tamper resistance and the content filter are on, " +
            "Guardian alerts are configured, and the accessibility service is running. Everything " +
            "else -- fine-tuning the filter server, protecting other specific apps, Knox setup -- " +
            "can be found in Settings any time.",
        onContinue = onFinish,
        continueLabel = "Finish",
    ) {}
}
