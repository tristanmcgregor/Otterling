package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudget
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudgetManager
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabit
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabitManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitProofLog
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitProofManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitProofRequirement
import au.com.tbmcgregor.bwparker.familyguard.focus.ImageMatcher
import au.com.tbmcgregor.bwparker.familyguard.focus.ProofSettings
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRule
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitShareApiClient
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitTrackerScanner
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulApp
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulAppManager
import au.com.tbmcgregor.bwparker.familyguard.focus.daysOfWeekSet
import au.com.tbmcgregor.bwparker.familyguard.focus.decodeDaysOfWeek
import au.com.tbmcgregor.bwparker.familyguard.focus.requiredHabitNames
import au.com.tbmcgregor.bwparker.familyguard.restrictions.AccessibilityGuard
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prerequisite for every feature below: friction screens, time budgets, and habit detection. If
 * this ever gets turned off, [au.com.tbmcgregor.bwparker.familyguard.tamper.AccessibilityGuardActivity]
 * takes over the screen until it's back on -- see [AccessibilityGuard] for why that's a nag rather
 * than a true block (Android has no Device Owner API to prevent disabling it outright). */
@Composable
fun AccessibilityServiceSection(context: Context) {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val enabled = remember(refreshTrigger) { AccessibilityGuard.isEnabled(context) }

    SectionCard(
        title = "Self-Improvement Engine",
        icon = Icons.Default.Accessibility,
        subtitle = "Powers the friction screens, time budgets, and habit check-ins below. Must " +
            "be turned on manually in Android's Accessibility settings -- if it's turned back " +
            "off, a full-screen reminder takes over the device until it's re-enabled.",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Accessibility service", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (enabled) "Enabled" else "Not enabled", isGood = enabled)
        }
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) {
            Text("Open Accessibility settings")
        }
        OutlinedButton(onClick = { refreshTrigger++ }) {
            Text("Refresh status")
        }
    }
}

@Composable
fun MindfulAppsSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val mindfulAppManager = remember { MindfulAppManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<MindfulApp>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showAppPicker by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) { apps = mindfulAppManager.apps() }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                coroutineScope.launch {
                    mindfulAppManager.add(app.packageName, delaySeconds = 20)
                    refreshTrigger++
                }
                showAppPicker = false
            },
        )
    }

    SectionCard(
        title = "Mindful Apps (Friction Screen)",
        icon = Icons.Default.Timelapse,
        subtitle = "Shows a short delay before opening, instead of a hard block -- for apps you " +
            "sometimes need but tend to open on autopilot.",
    ) {
        Button(onClick = {
            coroutineScope.launch {
                installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                showAppPicker = true
            }
        }) {
            Text("Choose app to add friction to")
        }
        if (apps.isEmpty()) {
            Text("No mindful apps configured yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            apps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${app.packageName} (${app.delaySeconds}s)", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        coroutineScope.launch {
                            mindfulAppManager.remove(app.packageName)
                            refreshTrigger++
                        }
                    }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
fun TimeBudgetsSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val budgetManager = remember { AppTimeBudgetManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var budgets by remember { mutableStateOf<List<AppTimeBudget>>(emptyList()) }
    var usageByPackage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var pendingPackage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTrigger) {
        budgets = budgetManager.budgets()
        usageByPackage = budgets.associate { it.packageName to budgetManager.todayCounter(it.packageName).totalSeconds }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                pendingPackage = app.packageName
                showAppPicker = false
            },
        )
    }

    pendingPackage?.let { packageName ->
        TimeBudgetInputDialog(
            packageName = packageName,
            onDismiss = { pendingPackage = null },
            onConfirm = { dailyLimitMinutes, subLimitMinutes, subLimitLabel ->
                coroutineScope.launch {
                    budgetManager.setBudget(packageName, dailyLimitMinutes, subLimitMinutes, subLimitLabel)
                    refreshTrigger++
                }
                pendingPackage = null
            },
        )
    }

    SectionCard(
        title = "Daily Time Budgets",
        icon = Icons.Default.Timelapse,
        subtitle = "Foreground time is measured by the accessibility service above, so it must " +
            "be enabled for this to work. The app is suspended for the rest of the day once a " +
            "limit is hit.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                        showAppPicker = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose app + set limits")
            }
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        budgetManager.setBudget(
                            packageName = "com.google.android.youtube",
                            dailyLimitMinutes = 120,
                            subLimitMinutes = 60,
                            subLimitLabel = "Shorts",
                        )
                        refreshTrigger++
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Quick add: YouTube (2h / 1h Shorts)")
            }
        }

        if (budgets.isEmpty()) {
            Text("No time budgets configured yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            budgets.forEach { budget ->
                val usedMinutes = (usageByPackage[budget.packageName] ?: 0) / 60
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(budget.packageName, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        coroutineScope.launch {
                            budgetManager.removeBudget(budget.packageName)
                            refreshTrigger++
                        }
                    }) {
                        Text("Remove")
                    }
                }
                val subText = budget.subLimitMinutes?.let { " (${budget.subLimitLabel ?: "sub-limit"}: ${it} min)" } ?: ""
                Text(
                    "Used today: $usedMinutes / ${budget.dailyLimitMinutes} min$subText",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TimeBudgetInputDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onConfirm: (dailyLimitMinutes: Int, subLimitMinutes: Int?, subLimitLabel: String?) -> Unit,
) {
    var totalMinutesText by remember { mutableStateOf("120") }
    var subLimitMinutesText by remember { mutableStateOf("") }
    var subLimitLabelText by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily budget for $packageName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = totalMinutesText,
                    onValueChange = { totalMinutesText = it },
                    label = { Text("Total daily limit (minutes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Optional stricter sub-limit for a feature the accessibility service can " +
                        "detect within this app (e.g. YouTube Shorts). Leave blank to skip.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = subLimitMinutesText,
                    onValueChange = { subLimitMinutesText = it },
                    label = { Text("Sub-limit (minutes, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = subLimitLabelText,
                    onValueChange = { subLimitLabelText = it },
                    label = { Text("Sub-limit label (e.g. Shorts)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val total = totalMinutesText.toIntOrNull() ?: return@Button
                val subLimit = subLimitMinutesText.toIntOrNull()
                onConfirm(total, subLimit, subLimitLabelText.takeIf { subLimit != null && it.isNotBlank() })
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Steps of the "add a rule" wizard: pick the trigger app, then which habit(s) within it gate the
 * block (any/all, or one-or-more specific detected habits that must ALL be done), then -- only
 * when specific habit(s) were picked -- whether the block only applies during a time-of-day
 * window, then the app that stays blocked until that condition is met, then confirm (asking how
 * long it unlocks for, unless it's time-windowed).
 */
/** Carries an existing rule's current values through the wizard so "Edit rule" can reuse the same
 * flow as "Add rule" -- each step pre-fills its dialog from this instead of starting blank, and
 * the final Confirm step updates the existing row (by [editingId]) instead of inserting a new one. */
private data class HabitRuleEditContext(
    val editingId: Long,
    val initialHabitNames: List<String>,
    val initialWindowStart: Int?,
    val initialWindowEnd: Int?,
    val initialDaysOfWeekMask: Int,
    val initialTargetPackageName: String,
    val initialUnlockMinutes: Int,
)

/** HabitShare is the only habit tracker this app knows how to read, so it's always the trigger --
 * the wizard skips straight to picking a condition instead of asking which app to use. Falls back
 * to a synthetic label if HabitShare isn't installed/scanned yet so the flow still works. */
private fun habitShareAppInfo(installedApps: List<InstalledAppInfo>): InstalledAppInfo =
    installedApps.find { it.packageName == HabitTrackerScanner.HABITSHARE_PACKAGE_NAME }
        ?: InstalledAppInfo(HabitTrackerScanner.HABITSHARE_PACKAGE_NAME, "HabitShare")

private sealed class HabitRuleWizardStep {
    data class PickCondition(val edit: HabitRuleEditContext?, val trigger: InstalledAppInfo) : HabitRuleWizardStep()
    data class PickWindow(
        val edit: HabitRuleEditContext?,
        val trigger: InstalledAppInfo,
        val habitNames: List<String>,
    ) : HabitRuleWizardStep()
    data class PickTarget(
        val edit: HabitRuleEditContext?,
        val trigger: InstalledAppInfo,
        val habitNames: List<String>,
        val windowStartMinute: Int?,
        val windowEndMinute: Int?,
        val daysOfWeek: Set<DayOfWeek>,
    ) : HabitRuleWizardStep()
    data class Confirm(
        val edit: HabitRuleEditContext?,
        val trigger: InstalledAppInfo,
        val habitNames: List<String>,
        val windowStartMinute: Int?,
        val windowEndMinute: Int?,
        val daysOfWeek: Set<DayOfWeek>,
        val target: InstalledAppInfo,
    ) : HabitRuleWizardStep()
}

/**
 * Full-screen HabitShare settings hub reached from the main Settings list. Groups everything
 * habit-related that used to be scattered inline: the account connection, per-habit image
 * verification, and the rule command-builder.
 */
@Composable
fun HabitShareSettingsScreen(context: Context, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("HabitShare", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HabitShareAccountSection(context)
            HabitShareVerificationSection(context)
            HabitRulesSection(context)
        }
    }
}

/**
 * Connect / disconnect the user's own HabitShare login. When connected, [HabitShareSyncManager]
 * reads exact done/not-done status directly from HabitShare's server every 30s instead of relying
 * on the on-screen scan.
 */
@Composable
fun HabitShareAccountSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val apiClient = remember { HabitShareApiClient(context) }
    var connected by remember { mutableStateOf(apiClient.isConnected()) }
    var username by remember { mutableStateOf(apiClient.connectedUsername().orEmpty()) }
    var showLoginForm by remember { mutableStateOf(false) }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

    SectionCard(
        title = "HabitShare Account",
        icon = Icons.Default.CloudSync,
        subtitle = "Connect your HabitShare login so completions are read straight from " +
            "HabitShare's own server -- far more reliable than reading them off the screen.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (connected) "Connected" else "Not connected",
                style = MaterialTheme.typography.bodyLarge,
            )
            StatusText(if (connected) "Live sync" else "Screen scan", isGood = connected)
        }
        if (connected) {
            Text("Signed in as $username", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                apiClient.disconnect()
                connected = false
                username = ""
            }) { Text("Disconnect") }
        } else {
            if (!showLoginForm) {
                Button(onClick = { showLoginForm = true }) { Text("Connect account") }
            } else {
                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    label = { Text("HabitShare username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("HabitShare password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                loginError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isLoggingIn && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
                        onClick = {
                            coroutineScope.launch {
                                isLoggingIn = true
                                loginError = null
                                when (apiClient.login(usernameInput, passwordInput)) {
                                    is HabitShareApiClient.LoginResult.Success -> {
                                        connected = true
                                        username = usernameInput
                                        showLoginForm = false
                                        passwordInput = ""
                                    }
                                    is HabitShareApiClient.LoginResult.InvalidCredentials ->
                                        loginError = "Incorrect username or password."
                                    is HabitShareApiClient.LoginResult.NetworkError ->
                                        loginError = "Couldn't reach HabitShare -- check your connection and try again."
                                }
                                isLoggingIn = false
                            }
                        },
                    ) { Text(if (isLoggingIn) "Connecting..." else "Log in") }
                    OutlinedButton(onClick = { showLoginForm = false; loginError = null }) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * "General settings" the user asked for: one checkbox per detected habit deciding whether ticking
 * it in HabitShare also demands a same-day photo that visually matches a reference photo before it
 * counts. Enforcement of "ticked yes + image verified" lives in [HabitProofManager.filterSatisfied]
 * / [HabitProofManager.namesNeedingProof]; this screen just configures it.
 */
@Composable
fun HabitShareVerificationSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val detectedHabitManager = remember { DetectedHabitManager(context) }
    val habitProofManager = remember { HabitProofManager(context) }
    val proofSettings = remember { ProofSettings(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var detectedHabits by remember { mutableStateOf<List<DetectedHabit>>(emptyList()) }
    var proofRequirements by remember { mutableStateOf<List<HabitProofRequirement>>(emptyList()) }
    var sensitivity by remember { mutableStateOf(proofSettings.sensitivity()) }

    LaunchedEffect(refreshTrigger) {
        detectedHabits = detectedHabitManager.latest()
        proofRequirements = habitProofManager.requirements()
    }

    SectionCard(
        title = "Image Verification",
        icon = Icons.Default.PhotoCamera,
        subtitle = "Tick a habit to require photo proof. When required, ticking it in HabitShare " +
            "isn't enough on its own -- it only counts toward unblocking an app once you've also " +
            "taken a same-day photo that visually matches a reference photo you set here.",
    ) {
        Text("Match strictness", style = MaterialTheme.typography.bodyLarge)
        Text(
            "How closely a daily photo must match a reference. Stricter rejects more (harder to " +
                "cheat); more lenient accepts more (fewer genuine photos refused).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageMatcher.Sensitivity.entries.forEach { option ->
                val label = when (option) {
                    ImageMatcher.Sensitivity.LENIENT -> "Lenient"
                    ImageMatcher.Sensitivity.NORMAL -> "Normal"
                    ImageMatcher.Sensitivity.STRICT -> "Strict"
                }
                if (option == sensitivity) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = {
                        sensitivity = option
                        proofSettings.setSensitivity(option)
                    }) { Text(label) }
                }
            }
        }
        HorizontalDivider()

        if (detectedHabits.isEmpty()) {
            Text(
                "No habits detected yet. Open HabitShare (or connect your account above) so your " +
                    "habits show up here, then refresh.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                detectedHabits.forEachIndexed { index, habit ->
                    if (index > 0) HorizontalDivider()
                    Text(habit.name, style = MaterialTheme.typography.bodyMedium)
                    ProofRequirementRow(
                        habitName = habit.name,
                        requirement = proofRequirements.find { it.habitName.equals(habit.name, ignoreCase = true) },
                        onSetRequirement = { required, referencePhotoPath ->
                            coroutineScope.launch {
                                habitProofManager.setRequirement(habit.name, required, referencePhotoPath)
                                proofRequirements = habitProofManager.requirements()
                            }
                        },
                    )
                }
            }
        }
        OutlinedButton(onClick = { refreshTrigger++ }) { Text("Refresh") }
    }
}

@Composable
fun HabitRulesSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val habitRuleManager = remember { HabitRuleManager(context) }
    val detectedHabitManager = remember { DetectedHabitManager(context) }
    val habitProofManager = remember { HabitProofManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf<List<HabitRule>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var detectedHabits by remember { mutableStateOf<List<DetectedHabit>>(emptyList()) }
    var proofRequirements by remember { mutableStateOf<List<HabitProofRequirement>>(emptyList()) }
    var proofLogs by remember { mutableStateOf<List<HabitProofLog>>(emptyList()) }
    var wizardStep by remember { mutableStateOf<HabitRuleWizardStep?>(null) }
    var showDetected by remember { mutableStateOf(false) }
    var showProofLog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        rules = habitRuleManager.rules()
        detectedHabits = detectedHabitManager.latest()
        proofRequirements = habitProofManager.requirements()
        if (installedApps.isEmpty()) {
            installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        }
    }

    /** Falls back to the raw package name if the app list hasn't loaded yet or it's been uninstalled. */
    fun appLabel(packageName: String): String =
        installedApps.find { it.packageName == packageName }?.label ?: packageName

    when (val step = wizardStep) {
        is HabitRuleWizardStep.PickCondition -> HabitConditionPickerDialog(
            detectedHabits = detectedHabits,
            initialHabitNames = step.edit?.initialHabitNames ?: emptyList(),
            proofRequirements = proofRequirements,
            onSetProofRequirement = { habitName, required, referencePhotoPath ->
                coroutineScope.launch {
                    habitProofManager.setRequirement(habitName, required, referencePhotoPath)
                    proofRequirements = habitProofManager.requirements()
                }
            },
            onDismiss = { wizardStep = null },
            onSelect = { habitNames ->
                // Time windows only make sense (and are only checkable outside a live scan) when
                // gating on specific habit(s), not the "any/all habits" whole-tracker pattern.
                wizardStep = if (habitNames.isEmpty()) {
                    HabitRuleWizardStep.PickTarget(step.edit, step.trigger, habitNames, null, null, DayOfWeek.entries.toSet())
                } else {
                    HabitRuleWizardStep.PickWindow(step.edit, step.trigger, habitNames)
                }
            },
            onSelectTimeOnly = {
                // No habit condition at all -- always blocked for a chosen time window (e.g. "no
                // phone before 9am"). Goes straight to the window picker; there's no "always"
                // option for this path since an unconditional, un-windowed rule would just be a
                // permanent block with no way to ever unlock it.
                wizardStep = HabitRuleWizardStep.PickWindow(step.edit, step.trigger, emptyList())
            },
        )
        is HabitRuleWizardStep.PickWindow -> HabitWindowPickerDialog(
            habitNames = step.habitNames,
            initialWindowStart = step.edit?.initialWindowStart,
            initialWindowEnd = step.edit?.initialWindowEnd,
            initialDaysOfWeek = step.edit?.initialDaysOfWeekMask?.let(::decodeDaysOfWeek),
            onDismiss = { wizardStep = null },
            onSelect = { start, end, daysOfWeek ->
                wizardStep = HabitRuleWizardStep.PickTarget(step.edit, step.trigger, step.habitNames, start, end, daysOfWeek)
            },
        )
        is HabitRuleWizardStep.PickTarget -> AppPickerDialog(
            apps = installedApps,
            initialQuery = installedApps.find { it.packageName == step.edit?.initialTargetPackageName }?.label ?: "",
            onDismiss = { wizardStep = null },
            onSelect = { app ->
                wizardStep = HabitRuleWizardStep.Confirm(
                    step.edit,
                    step.trigger,
                    step.habitNames,
                    step.windowStartMinute,
                    step.windowEndMinute,
                    step.daysOfWeek,
                    app,
                )
            },
        )
        is HabitRuleWizardStep.Confirm -> {
            val windowStart = step.windowStartMinute
            val windowEnd = step.windowEndMinute
            val editingId = step.edit?.editingId
            if (windowStart != null && windowEnd != null) {
                HabitRuleWindowConfirmDialog(
                    triggerLabel = step.trigger.label,
                    habitNames = step.habitNames,
                    windowStartMinute = windowStart,
                    windowEndMinute = windowEnd,
                    daysOfWeek = step.daysOfWeek,
                    targetLabel = step.target.label,
                    isEditing = editingId != null,
                    onDismiss = { wizardStep = null },
                    onConfirm = {
                        coroutineScope.launch {
                            if (editingId != null) {
                                habitRuleManager.updateRule(
                                    id = editingId,
                                    triggerPackageName = step.trigger.packageName,
                                    targetPackageName = step.target.packageName,
                                    unlockMinutes = 0,
                                    requiredHabitNames = step.habitNames,
                                    windowStartMinute = windowStart,
                                    windowEndMinute = windowEnd,
                                    daysOfWeek = step.daysOfWeek,
                                )
                            } else {
                                habitRuleManager.addRule(
                                    triggerPackageName = step.trigger.packageName,
                                    targetPackageName = step.target.packageName,
                                    unlockMinutes = 0,
                                    requiredHabitNames = step.habitNames,
                                    windowStartMinute = windowStart,
                                    windowEndMinute = windowEnd,
                                    daysOfWeek = step.daysOfWeek,
                                )
                            }
                            refreshTrigger++
                        }
                        wizardStep = null
                    },
                )
            } else {
                HabitRuleMinutesDialog(
                    triggerLabel = step.trigger.label,
                    habitNames = step.habitNames,
                    targetLabel = step.target.label,
                    initialMinutes = step.edit?.initialUnlockMinutes ?: 30,
                    isEditing = editingId != null,
                    onDismiss = { wizardStep = null },
                    onConfirm = { minutes ->
                        coroutineScope.launch {
                            if (editingId != null) {
                                habitRuleManager.updateRule(
                                    id = editingId,
                                    triggerPackageName = step.trigger.packageName,
                                    targetPackageName = step.target.packageName,
                                    unlockMinutes = minutes,
                                    requiredHabitNames = step.habitNames,
                                )
                            } else {
                                habitRuleManager.addRule(
                                    step.trigger.packageName,
                                    step.target.packageName,
                                    minutes,
                                    step.habitNames,
                                )
                            }
                            refreshTrigger++
                        }
                        wizardStep = null
                    },
                )
            }
        }
        null -> {}
    }

    SectionCard(
        title = "Habit Rules (Command Builder)",
        icon = Icons.Default.PlayArrow,
        subtitle = "Build as many \"(app) is blocked until (habit(s) done in HabitShare), then " +
            "unlocks for (X) minutes\" commands as you like -- gate on any/all habits, or require " +
            "one or more specific habits (ALL of them must be done) auto-detected from HabitShare's " +
            "screen. The target app stays blocked until its condition is met for the day; the " +
            "unlock window then counts down automatically.",
    ) {
        Button(onClick = {
            coroutineScope.launch {
                if (installedApps.isEmpty()) {
                    installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                }
                detectedHabits = detectedHabitManager.latest()
                wizardStep = HabitRuleWizardStep.PickCondition(edit = null, trigger = habitShareAppInfo(installedApps))
            }
        }) {
            Text("Add rule")
        }

        if (rules.isEmpty()) {
            Text("No rules yet -- add one above.", style = MaterialTheme.typography.bodySmall)
        } else {
            val now = System.currentTimeMillis()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rules.forEach { rule ->
                    HabitRuleRow(
                        rule = rule,
                        triggerLabel = appLabel(rule.triggerPackageName),
                        targetLabel = appLabel(rule.targetPackageName),
                        now = now,
                        onEdit = {
                            coroutineScope.launch {
                                if (installedApps.isEmpty()) {
                                    installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                                }
                                detectedHabits = detectedHabitManager.latest()
                                wizardStep = HabitRuleWizardStep.PickCondition(
                                    edit = HabitRuleEditContext(
                                        editingId = rule.id,
                                        initialHabitNames = rule.requiredHabitNames(),
                                        initialWindowStart = rule.windowStartMinute,
                                        initialWindowEnd = rule.windowEndMinute,
                                        initialDaysOfWeekMask = rule.daysOfWeekMask,
                                        initialTargetPackageName = rule.targetPackageName,
                                        initialUnlockMinutes = rule.unlockMinutes.takeIf { it > 0 } ?: 30,
                                    ),
                                    trigger = habitShareAppInfo(installedApps),
                                )
                            }
                        },
                        onEnabledChange = { enabled ->
                            coroutineScope.launch {
                                habitRuleManager.setEnabled(rule.id, enabled)
                                refreshTrigger++
                            }
                        },
                        onRemove = {
                            coroutineScope.launch {
                                habitRuleManager.deleteRule(rule.id)
                                refreshTrigger++
                            }
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        TextButton(onClick = { showDetected = !showDetected }) {
            Text(if (showDetected) "Hide detected habits" else "Show detected habits (tuning helper)")
        }
        if (showDetected) {
            Text(
                "Every habit row detected so far, and whether it's currently ticked for today. " +
                    "Open HabitShare (or connect your account in HabitShare settings) and refresh " +
                    "if a habit you expect isn't listed. Set which of these need image proof under " +
                    "\"Image verification\" in HabitShare settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (detectedHabits.isEmpty()) {
                Text("Nothing detected yet -- open the habit tracker app first.", style = MaterialTheme.typography.bodySmall)
            } else {
                detectedHabits.forEach { habit ->
                    val needsProof = proofRequirements.any {
                        it.habitName.equals(habit.name, ignoreCase = true) &&
                            it.required && !it.referencePhotoPath.isNullOrBlank()
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            habit.name + if (needsProof) "  (image proof)" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        StatusText(if (habit.doneToday) "Done" else "Not done", isGood = habit.doneToday)
                    }
                }
            }
            OutlinedButton(onClick = { refreshTrigger++ }) {
                Text("Refresh")
            }
        }

        HorizontalDivider()

        TextButton(onClick = {
            showProofLog = !showProofLog
            if (showProofLog) {
                coroutineScope.launch { proofLogs = habitProofManager.recentLogs() }
            }
        }) {
            Text(if (showProofLog) "Hide proof log" else "Show submitted proof log")
        }
        if (showProofLog) {
            if (proofLogs.isEmpty()) {
                Text("No proof submitted yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    proofLogs.forEach { log ->
                        HabitProofLogRow(
                            log = log,
                            onRemove = {
                                coroutineScope.launch {
                                    habitProofManager.deleteLog(log.habitName, log.dateEpochDay)
                                    habitRuleManager.reapplyAll()
                                    proofLogs = habitProofManager.recentLogs()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Requires image proof" checkbox for one habit: turning it on immediately opens the camera to
 * take a reference photo (required before it can be turned on at all -- there's nothing to compare
 * against otherwise); turning it off clears the requirement and all reference photos. You can add
 * several reference photos (e.g. different angles/lighting) -- a daily proof only has to match any
 * one of them, which makes genuine matches far more forgiving without loosening the threshold.
 * Shown both in the rule-builder condition picker and in the standalone detected-habits tuning
 * list, sharing the same underlying [HabitProofRequirement] since multiple rules can gate on the
 * same habit name.
 */
@Composable
private fun ProofRequirementRow(
    habitName: String,
    requirement: HabitProofRequirement?,
    onSetRequirement: (required: Boolean, referencePhotoPath: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refVersion by remember(habitName) { mutableIntStateOf(0) }
    val references = remember(habitName, refVersion, requirement?.referencePhotoPath) {
        HabitProofManager.referenceFiles(context, habitName, requirement?.referencePhotoPath)
    }
    // A row flagged required but with no readable reference photo (e.g. a relic of an older build)
    // can never be satisfied -- show it unchecked so re-checking walks through capturing one again.
    val required = requirement?.required == true && references.isNotEmpty()

    var pendingCapture by remember { mutableStateOf<File?>(null) }
    val takeReferencePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val target = pendingCapture
        pendingCapture = null
        if (success && target != null) {
            refVersion++
            onSetRequirement(true, target.absolutePath)
        }
    }

    fun captureReference() {
        val target = HabitProofManager.newReferenceFile(context, habitName)
        pendingCapture = target
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        takeReferencePhoto.launch(uri)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                if (required) {
                    HabitProofManager.clearReferences(context, habitName)
                    refVersion++
                    onSetRequirement(false, null)
                } else {
                    captureReference()
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = required,
                onCheckedChange = { checked ->
                    if (checked) {
                        captureReference()
                    } else {
                        HabitProofManager.clearReferences(context, habitName)
                        refVersion++
                        onSetRequirement(false, null)
                    }
                },
            )
            Text("Requires image proof (must match a reference photo)", style = MaterialTheme.typography.bodySmall)
        }
        if (required) {
            Column(modifier = Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                references.forEach { ref ->
                    // Decoding is real file + JPEG-decode I/O -- doing it inline inside `remember{}`
                    // ran it synchronously on the main/composition thread for every reference photo
                    // row, every time this recomposed. Loading it asynchronously via LaunchedEffect
                    // keeps composition itself non-blocking; the row just shows no image for one
                    // frame while it decodes.
                    var bitmap by remember(ref.absolutePath, ref.lastModified()) { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(ref.absolutePath, ref.lastModified()) {
                        bitmap = withContext(Dispatchers.IO) {
                            runCatching {
                                BitmapFactory.Options().apply { inSampleSize = 4 }
                                    .let { opts -> BitmapFactory.decodeFile(ref.absolutePath, opts) }
                            }.getOrNull()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        bitmap?.let { loadedBitmap ->
                            Image(
                                bitmap = loadedBitmap.asImageBitmap(),
                                contentDescription = "Reference photo for $habitName",
                                modifier = Modifier.height(56.dp),
                            )
                        }
                        TextButton(onClick = {
                            HabitProofManager.deleteReference(ref)
                            refVersion++
                            val remaining = HabitProofManager.referenceFiles(context, habitName)
                            if (remaining.isEmpty()) onSetRequirement(false, null)
                            else onSetRequirement(true, remaining.first().absolutePath)
                        }) { Text("Remove") }
                    }
                }
                TextButton(onClick = { captureReference() }) {
                    Text("Add another reference photo")
                }
            }
        }
    }
}

@Composable
private fun HabitProofLogRow(log: HabitProofLog, onRemove: () -> Unit) {
    var bitmap by remember(log.photoPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(log.photoPath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.Options().apply { inSampleSize = 4 }
                    .let { opts -> BitmapFactory.decodeFile(log.photoPath, opts) }
            }.getOrNull()
        }
    }
    var confirmingRemove by remember(log) { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(log.habitName, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { confirmingRemove = true }) { Text("Remove") }
            }
            Text(
                java.time.LocalDate.ofEpochDay(log.dateEpochDay).toString(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (log.note.isNotBlank()) {
                Text(log.note, style = MaterialTheme.typography.bodySmall)
            }
            bitmap?.let { loadedBitmap ->
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = loadedBitmap.asImageBitmap(),
                    contentDescription = "Proof photo for ${log.habitName}",
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }
        }
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove this proof?") },
            text = {
                Text(
                    "\"${log.habitName}\" on ${java.time.LocalDate.ofEpochDay(log.dateEpochDay)} will no " +
                        "longer count as done today -- any rule gated on it may re-block immediately.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingRemove = false; onRemove() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
            },
        )
    }
}

/** One rule's card: condition/status on top, enabled switch + edit/remove actions on a tidy
 * bottom row -- replaces the old single cramped [Row] that pushed buttons off narrower screens. */
@Composable
private fun HabitRuleRow(
    rule: HabitRule,
    triggerLabel: String,
    targetLabel: String,
    now: Long,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val required = rule.requiredHabitNames()
            val windowStart = rule.windowStartMinute
            val windowEnd = rule.windowEndMinute
            val daysOfWeek = rule.daysOfWeekSet()
            // Empty required-habits means different things depending on whether it's windowed --
            // see HabitRule's doc.
            val timeOnly = windowStart != null && windowEnd != null && required.isEmpty()
            val conditionLabel = if (required.isEmpty()) "all habits" else required.joinToString(" AND ")
            Text(
                if (timeOnly) "$targetLabel blocked" else "$targetLabel blocked until \"$conditionLabel\" done in $triggerLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (windowStart != null && windowEnd != null) {
                Text(
                    if (timeOnly) {
                        "-> ${formatDaysOfWeek(daysOfWeek)} ${formatMinuteOfDay(windowStart)}-${formatMinuteOfDay(windowEnd)}, no habit needed"
                    } else {
                        "-> only enforced ${formatDaysOfWeek(daysOfWeek)} ${formatMinuteOfDay(windowStart)}-${formatMinuteOfDay(windowEnd)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val unlocked = !isRuleCurrentlyWindowed(windowStart, windowEnd, daysOfWeek)
                StatusText(
                    if (unlocked) "Outside its window right now" else if (timeOnly) "Inside its window -- blocked" else "Inside its window -- blocked until done",
                    isGood = unlocked,
                )
            } else {
                Text(
                    "-> then unlocked for ${rule.unlockMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                )
                val unlocked = rule.unlockUntilMillis > now
                StatusText(
                    if (unlocked) "Unlocked (until you use it up)" else "Blocked, waiting on today's habit(s)",
                    isGood = unlocked,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (rule.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = rule.enabled, onCheckedChange = onEnabledChange)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit rule")
                    }
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove rule",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitConditionPickerDialog(
    detectedHabits: List<DetectedHabit>,
    initialHabitNames: List<String> = emptyList(),
    proofRequirements: List<HabitProofRequirement> = emptyList(),
    onSetProofRequirement: (habitName: String, required: Boolean, referencePhotoPath: String?) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit,
    onSelect: (habitNames: List<String>) -> Unit,
    onSelectTimeOnly: () -> Unit,
) {
    val detectedNames = remember(detectedHabits) { detectedHabits.map { it.name }.toSet() }
    var selected by remember {
        mutableStateOf(initialHabitNames.filter { it in detectedNames }.toSet())
    }
    var customNames by remember {
        mutableStateOf(initialHabitNames.filterNot { it in detectedNames })
    }
    var customName by remember { mutableStateOf("") }

    fun toggle(name: String) {
        selected = if (name in selected) selected - name else selected + name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which habit(s) must be done to unlock the target app?") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onSelect(emptyList()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Any/all habits complete")
                }
                OutlinedButton(onClick = onSelectTimeOnly, modifier = Modifier.fillMaxWidth()) {
                    Text("No habit needed -- just block during specific hours")
                }
                Text(
                    "Or require one or more specific habits below -- ALL selected habits must be " +
                        "done today for the app to unlock:",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (detectedHabits.isNotEmpty()) {
                    detectedHabits.forEach { habit ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toggle(habit.name) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = habit.name in selected, onCheckedChange = { toggle(habit.name) })
                                Text(habit.name)
                            }
                            ProofRequirementRow(
                                habitName = habit.name,
                                requirement = proofRequirements.find { it.habitName.equals(habit.name, ignoreCase = true) },
                                onSetRequirement = { required, referencePhotoPath ->
                                    onSetProofRequirement(habit.name, required, referencePhotoPath)
                                },
                                modifier = Modifier.padding(start = 40.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        "No habits detected yet -- open the tracker app first, or type name(s) below:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                customNames.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+ $name", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { customNames = customNames - name }) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Custom habit name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val trimmed = customName.trim()
                        if (trimmed.isNotBlank() && trimmed !in customNames) customNames = customNames + trimmed
                        customName = ""
                    }) {
                        Text("Add")
                    }
                }
                val required = (selected + customNames).toList()
                Button(
                    onClick = { onSelect(required) },
                    enabled = required.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (required.size <= 1) "Continue" else "Continue (requires all ${required.size})")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun HabitWindowPickerDialog(
    habitNames: List<String>,
    initialWindowStart: Int? = null,
    initialWindowEnd: Int? = null,
    initialDaysOfWeek: Set<DayOfWeek>? = null,
    onDismiss: () -> Unit,
    onSelect: (windowStartMinute: Int?, windowEndMinute: Int?, daysOfWeek: Set<DayOfWeek>) -> Unit,
) {
    // A "time only" rule (no habit condition) has no "Always" option -- an unwindowed rule with no
    // condition to ever satisfy would just be a permanent, un-liftable block -- so it always shows
    // the time fields directly instead of asking "always vs. windowed" first.
    val timeOnly = habitNames.isEmpty()
    var showCustom by remember { mutableStateOf(timeOnly || (initialWindowStart != null && initialWindowEnd != null)) }
    var startText by remember { mutableStateOf(initialWindowStart?.let { formatMinuteOfDay(it) } ?: "00:00") }
    var endText by remember { mutableStateOf(initialWindowEnd?.let { formatMinuteOfDay(it) } ?: if (timeOnly) "09:00" else "21:00") }
    var selectedDays by remember { mutableStateOf(initialDaysOfWeek ?: DayOfWeek.entries.toSet()) }
    val conditionLabel = habitNames.joinToString(" AND ")
    val start = parseTimeToMinuteOfDay(startText)
    val end = parseTimeToMinuteOfDay(endText)

    fun toggleDay(day: DayOfWeek) {
        val updated = if (day in selectedDays) selectedDays - day else selectedDays + day
        // Don't allow deselecting down to zero days -- that's not a meaningful "never" choice,
        // and matches encodeDaysOfWeek() treating an empty set as "every day" anyway.
        if (updated.isNotEmpty()) selectedDays = updated
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (timeOnly) "When should this app be blocked?" else "When should blocking on \"$conditionLabel\" apply?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!timeOnly) {
                    OutlinedButton(onClick = { onSelect(null, null, selectedDays) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Always (no time restriction)")
                    }
                    OutlinedButton(onClick = { showCustom = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Only during a specific time window")
                    }
                }
                if (showCustom) {
                    Text(
                        if (timeOnly) {
                            "Blocks the app every day from the start time until the end time, " +
                                "unconditionally -- no habit needs to be done. Use 00:00 as the " +
                                "end time to mean \"through to midnight\" -- e.g. start 21:00, " +
                                "end 00:00 covers 9pm-midnight."
                        } else {
                            "Blocks (while \"$conditionLabel\" isn't done yet today) from the start " +
                                "time until the end time. Use 00:00 as the end time to mean " +
                                "\"through to midnight\" -- e.g. start 21:00, end 00:00 covers 9pm-midnight."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Start time (24h, e.g. 21:00)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("End time (24h, e.g. 00:00 for midnight)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (start == null || end == null) {
                        Text("Enter times as HH:mm, e.g. 21:00", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Which days?", style = MaterialTheme.typography.bodySmall)
                    DayOfWeekPicker(selectedDays = selectedDays, onToggle = ::toggleDay)
                    Button(
                        onClick = { if (start != null && end != null) onSelect(start, end, selectedDays) },
                        enabled = start != null && end != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Compact Mon-Sun toggle row for restricting a windowed rule to specific days -- at least one
 * day must stay selected (see [HabitWindowPickerDialog.toggleDay]). */
@Composable
private fun DayOfWeekPicker(selectedDays: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in selectedDays
            FilterChip(
                selected = selected,
                onClick = { onToggle(day) },
                label = { Text(day.name.take(1) + day.name.drop(1).take(1).lowercase()) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Every day" if all 7 are selected, otherwise a short comma-joined list, e.g. "Mon, Wed, Fri". */
private fun formatDaysOfWeek(days: Set<DayOfWeek>): String {
    if (days.size == DayOfWeek.entries.size) return "every day"
    return DayOfWeek.entries.filter { it in days }
        .joinToString(", ") { it.name.take(1) + it.name.drop(1).take(2).lowercase() }
}

@Composable
private fun HabitRuleWindowConfirmDialog(
    triggerLabel: String,
    habitNames: List<String>,
    windowStartMinute: Int,
    windowEndMinute: Int,
    daysOfWeek: Set<DayOfWeek>,
    targetLabel: String,
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val conditionLabel = habitNames.joinToString(" AND ")
    val daysLabel = formatDaysOfWeek(daysOfWeek)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Confirm changes" else "Confirm rule") },
        text = {
            Text(
                if (habitNames.isEmpty()) {
                    "$targetLabel will be blocked $daysLabel from ${formatMinuteOfDay(windowStartMinute)} " +
                        "to ${formatMinuteOfDay(windowEndMinute)}, unconditionally -- no habit needed. " +
                        "No time restriction outside that window (or on other days)."
                } else {
                    "$targetLabel will be blocked $daysLabel from ${formatMinuteOfDay(windowStartMinute)} to " +
                        "${formatMinuteOfDay(windowEndMinute)} unless \"$conditionLabel\" is done in " +
                        "$triggerLabel that day. No time restriction outside that window (or on other days)."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(if (isEditing) "Save changes" else "Save rule") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun parseTimeToMinuteOfDay(text: String): Int? {
    val match = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, 1439)
    return "%02d:%02d".format(clamped / 60, clamped % 60)
}

/** UI-only display hint: whether the current time falls inside [start, end) (wrapping at
 * midnight if end <= start), independent of whether the required habit(s) are done. */
private fun isRuleCurrentlyWindowed(start: Int, end: Int, daysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet()): Boolean {
    if (java.time.LocalDate.now().dayOfWeek !in daysOfWeek) return false
    val now = java.time.LocalTime.now()
    val minuteOfDay = now.hour * 60 + now.minute
    return if (start <= end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
}

@Composable
private fun HabitRuleMinutesDialog(
    triggerLabel: String,
    habitNames: List<String>,
    targetLabel: String,
    initialMinutes: Int = 30,
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (unlockMinutes: Int) -> Unit,
) {
    var minutesText by remember { mutableStateOf(initialMinutes.toString()) }
    val conditionLabel = if (habitNames.isEmpty()) "all habits" else habitNames.joinToString(" AND ")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$targetLabel is blocked until \"$conditionLabel\" done in $triggerLabel...") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "...then unlock $targetLabel for how many minutes?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it },
                    label = { Text("Unlock minutes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val minutes = minutesText.toIntOrNull() ?: return@Button
                if (minutes > 0) onConfirm(minutes)
            }) {
                Text(if (isEditing) "Save changes" else "Save rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
