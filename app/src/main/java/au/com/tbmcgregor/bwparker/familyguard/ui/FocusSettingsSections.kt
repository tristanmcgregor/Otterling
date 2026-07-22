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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudget
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudgetManager
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabit
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabitManager
import au.com.tbmcgregor.bwparker.familyguard.focus.FocusGuardAccessibilityService
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRule
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleManager
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
 * block (any/all, or one-or-more specific detected habits that must ALL be done), then the app
 * that stays blocked until that condition is met, then how long it unlocks for.
 */
private sealed class HabitRuleWizardStep {
    object PickTrigger : HabitRuleWizardStep()
    data class PickCondition(val trigger: InstalledAppInfo) : HabitRuleWizardStep()
    data class PickTarget(val trigger: InstalledAppInfo, val habitNames: List<String>) : HabitRuleWizardStep()
    data class Confirm(val trigger: InstalledAppInfo, val habitNames: List<String>, val target: InstalledAppInfo) :
        HabitRuleWizardStep()
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
    }

    when (val step = wizardStep) {
        is HabitRuleWizardStep.PickTrigger -> AppPickerDialog(
            apps = installedApps,
            onDismiss = { wizardStep = null },
            onSelect = { app -> wizardStep = HabitRuleWizardStep.PickCondition(app) },
        )
        is HabitRuleWizardStep.PickCondition -> HabitConditionPickerDialog(
            detectedHabits = detectedHabits,
            onDismiss = { wizardStep = null },
            onSelect = { habitNames -> wizardStep = HabitRuleWizardStep.PickTarget(step.trigger, habitNames) },
        )
        is HabitRuleWizardStep.PickTarget -> AppPickerDialog(
            apps = installedApps,
            onDismiss = { wizardStep = null },
            onSelect = { app -> wizardStep = HabitRuleWizardStep.Confirm(step.trigger, step.habitNames, app) },
        )
        is HabitRuleWizardStep.Confirm -> HabitRuleMinutesDialog(
            triggerLabel = step.trigger.label,
            habitNames = step.habitNames,
            targetLabel = step.target.label,
            onDismiss = { wizardStep = null },
            onConfirm = { minutes ->
                coroutineScope.launch {
                    habitRuleManager.addRule(
                        step.trigger.packageName,
                        step.target.packageName,
                        minutes,
                        step.habitNames,
                    )
                    refreshTrigger++
                }
                wizardStep = null
            },
        )
        null -> {}
    }

    SectionCard(
        title = "Habit Rules (Command Builder)",
        icon = Icons.Default.PlayArrow,
        subtitle = "Build as many \"(app B) is blocked until (habit(s) done in app A), then unlocks " +
            "for (X) minutes\" commands as you like -- gate on any/all habits, or require one or " +
            "more specific habits (ALL of them must be done) auto-detected from the tracker's " +
            "screen. The target app stays blocked until its condition is met for the day; the " +
            "unlock window then counts down automatically.",
    ) {
        Button(onClick = {
            coroutineScope.launch {
                installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                detectedHabits = detectedHabitManager.latest()
                wizardStep = HabitRuleWizardStep.PickTrigger
            }
        }) {
            Text("Add rule")
        }

        if (rules.isEmpty()) {
            Text("No rules yet -- add one above.", style = MaterialTheme.typography.bodySmall)
        } else {
            val now = System.currentTimeMillis()
            rules.forEach { rule ->
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val required = rule.requiredHabitNames()
                        val conditionLabel = if (required.isEmpty()) "all habits" else required.joinToString(" AND ")
                        Text(
                            "${rule.targetPackageName} blocked until \"$conditionLabel\" done in " +
                                rule.triggerPackageName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                habitRuleManager.setEnabled(rule.id, enabled)
                                refreshTrigger++
                            }
                        },
                    )
                    TextButton(onClick = {
                        coroutineScope.launch {
                            habitRuleManager.deleteRule(rule.id)
                            refreshTrigger++
                        }
                    }) {
                        Text("Remove")
                    }
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

@Composable
private fun HabitConditionPickerDialog(
    detectedHabits: List<DetectedHabit>,
    onDismiss: () -> Unit,
    onSelect: (habitNames: List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var customName by remember { mutableStateOf("") }

    fun toggle(name: String) {
        selected = if (name in selected) selected - name else selected + name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which habit(s) must be done to unlock the target app?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelect(emptyList()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Any/all habits complete")
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
private fun HabitRuleMinutesDialog(
    triggerLabel: String,
    habitNames: List<String>,
    targetLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (unlockMinutes: Int) -> Unit,
) {
    var minutesText by remember { mutableStateOf("30") }
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
                Text("Save rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
