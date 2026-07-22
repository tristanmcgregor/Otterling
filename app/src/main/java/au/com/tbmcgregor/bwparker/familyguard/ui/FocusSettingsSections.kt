package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudget
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudgetManager
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabit
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabitManager
import au.com.tbmcgregor.bwparker.familyguard.focus.FocusGuardAccessibilityService
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRule
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitTrackerScanner
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulApp
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulAppManager
import au.com.tbmcgregor.bwparker.familyguard.focus.requiredHabitNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, FocusGuardAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

/** Prerequisite for every feature below: friction screens, time budgets, and habit detection. */
@Composable
fun AccessibilityServiceSection(context: Context) {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val enabled = remember(refreshTrigger) { isAccessibilityServiceEnabled(context) }

    SectionCard(
        title = "Self-Improvement Engine",
        icon = Icons.Default.Accessibility,
        subtitle = "Powers the friction screens, time budgets, and habit check-ins below. " +
            "Must be turned on manually in Android's Accessibility settings.",
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
    ) : HabitRuleWizardStep()
    data class Confirm(
        val edit: HabitRuleEditContext?,
        val trigger: InstalledAppInfo,
        val habitNames: List<String>,
        val windowStartMinute: Int?,
        val windowEndMinute: Int?,
        val target: InstalledAppInfo,
    ) : HabitRuleWizardStep()
}

@Composable
fun HabitRulesSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val habitRuleManager = remember { HabitRuleManager(context) }
    val detectedHabitManager = remember { DetectedHabitManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf<List<HabitRule>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var detectedHabits by remember { mutableStateOf<List<DetectedHabit>>(emptyList()) }
    var wizardStep by remember { mutableStateOf<HabitRuleWizardStep?>(null) }
    var showDetected by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        rules = habitRuleManager.rules()
        detectedHabits = detectedHabitManager.latest()
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
            onDismiss = { wizardStep = null },
            onSelect = { habitNames ->
                // Time windows only make sense (and are only checkable outside a live scan) when
                // gating on specific habit(s), not the "any/all habits" whole-tracker pattern.
                wizardStep = if (habitNames.isEmpty()) {
                    HabitRuleWizardStep.PickTarget(step.edit, step.trigger, habitNames, null, null)
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
            onDismiss = { wizardStep = null },
            onSelect = { start, end ->
                wizardStep = HabitRuleWizardStep.PickTarget(step.edit, step.trigger, step.habitNames, start, end)
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
                                )
                            } else {
                                habitRuleManager.addRule(
                                    triggerPackageName = step.trigger.packageName,
                                    targetPackageName = step.target.packageName,
                                    unlockMinutes = 0,
                                    requiredHabitNames = step.habitNames,
                                    windowStartMinute = windowStart,
                                    windowEndMinute = windowEnd,
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
                "Every habit row the scanner has found so far, and whether it looked checked off " +
                    "the last time its screen was open. Open the tracker app, then come back here " +
                    "and refresh if a habit you expect isn't listed.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (detectedHabits.isEmpty()) {
                Text("Nothing detected yet -- open the habit tracker app first.", style = MaterialTheme.typography.bodySmall)
            } else {
                detectedHabits.forEach { habit ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(habit.name, style = MaterialTheme.typography.bodySmall)
                        StatusText(if (habit.doneToday) "Done" else "Not done", isGood = habit.doneToday)
                    }
                }
            }
            OutlinedButton(onClick = { refreshTrigger++ }) {
                Text("Refresh")
            }
        }
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
                        "-> every day ${formatMinuteOfDay(windowStart)}-${formatMinuteOfDay(windowEnd)}, no habit needed"
                    } else {
                        "-> only enforced ${formatMinuteOfDay(windowStart)}-${formatMinuteOfDay(windowEnd)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val unlocked = !isRuleCurrentlyWindowed(windowStart, windowEnd)
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggle(habit.name) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = habit.name in selected, onCheckedChange = { toggle(habit.name) })
                            Text(habit.name)
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
    onDismiss: () -> Unit,
    onSelect: (windowStartMinute: Int?, windowEndMinute: Int?) -> Unit,
) {
    // A "time only" rule (no habit condition) has no "Always" option -- an unwindowed rule with no
    // condition to ever satisfy would just be a permanent, un-liftable block -- so it always shows
    // the time fields directly instead of asking "always vs. windowed" first.
    val timeOnly = habitNames.isEmpty()
    var showCustom by remember { mutableStateOf(timeOnly || (initialWindowStart != null && initialWindowEnd != null)) }
    var startText by remember { mutableStateOf(initialWindowStart?.let { formatMinuteOfDay(it) } ?: "00:00") }
    var endText by remember { mutableStateOf(initialWindowEnd?.let { formatMinuteOfDay(it) } ?: if (timeOnly) "09:00" else "21:00") }
    val conditionLabel = habitNames.joinToString(" AND ")
    val start = parseTimeToMinuteOfDay(startText)
    val end = parseTimeToMinuteOfDay(endText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (timeOnly) "When should this app be blocked?" else "When should blocking on \"$conditionLabel\" apply?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!timeOnly) {
                    OutlinedButton(onClick = { onSelect(null, null) }, modifier = Modifier.fillMaxWidth()) {
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
                    Button(
                        onClick = { if (start != null && end != null) onSelect(start, end) },
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

@Composable
private fun HabitRuleWindowConfirmDialog(
    triggerLabel: String,
    habitNames: List<String>,
    windowStartMinute: Int,
    windowEndMinute: Int,
    targetLabel: String,
    isEditing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val conditionLabel = habitNames.joinToString(" AND ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Confirm changes" else "Confirm rule") },
        text = {
            Text(
                if (habitNames.isEmpty()) {
                    "$targetLabel will be blocked every day from ${formatMinuteOfDay(windowStartMinute)} " +
                        "to ${formatMinuteOfDay(windowEndMinute)}, unconditionally -- no habit needed. " +
                        "No time restriction outside that window."
                } else {
                    "$targetLabel will be blocked from ${formatMinuteOfDay(windowStartMinute)} to " +
                        "${formatMinuteOfDay(windowEndMinute)} unless \"$conditionLabel\" is done in " +
                        "$triggerLabel that day. No time restriction outside that window."
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
private fun isRuleCurrentlyWindowed(start: Int, end: Int): Boolean {
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
