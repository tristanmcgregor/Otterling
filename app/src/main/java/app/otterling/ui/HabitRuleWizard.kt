package app.otterling.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import app.otterling.focus.DetectedHabit
import app.otterling.focus.DetectedHabitManager
import app.otterling.focus.HabitRule
import app.otterling.focus.HabitRuleManager
import app.otterling.focus.HabitTrackerScanner
import app.otterling.focus.decodeDaysOfWeek
import app.otterling.focus.requiredHabitNames
import app.otterling.focus.targetPackageNames
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * Reusable rule wizard, rendered as a single full-screen 3-step "Create Rule" stepper matching the
 * Figma design. Render it only while a wizard is requested; a null [ruleToEdit] creates a new rule,
 * while an existing rule pre-fills every step and switches Save to [HabitRuleManager.updateRule].
 *
 * Functionality preserved from the old multi-dialog chain:
 *  - the trigger app is always HabitShare ([habitShareAppInfo]) -- never chosen by the user;
 *  - the target app is picked from [loadInstalledApps] and is searchable (step 1);
 *  - required habits are a multi-select of [DetectedHabitManager.latest] (step 2); selecting none
 *    yields a time-only rule;
 *  - BOTH scheduling modes remain reachable (step 3): a time window saves with unlockMinutes = 0,
 *    while leaving the window off collects an unlock duration (minutes, default 30) and saves with
 *    a null window. Days-of-week default to all 7 and are encoded via the existing mask helpers.
 */
@Composable
fun HabitRuleWizardHost(
    context: Context,
    ruleToEdit: HabitRule? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val ruleManager = remember { HabitRuleManager(context) }
    val detectedHabitManager = remember { DetectedHabitManager(context) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var detectedHabits by remember { mutableStateOf<List<DetectedHabit>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }

    var step by remember { mutableIntStateOf(1) }
    var selectedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedHabits by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Habit names on an edited rule that aren't in the currently-detected list (e.g. typed by the
    // old custom-name flow) are still offered as pills so editing preserves them.
    var extraHabitNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var windowEnabled by remember { mutableStateOf(false) }
    var startText by remember { mutableStateOf("00:00") }
    var endText by remember { mutableStateOf("21:00") }
    var minutesText by remember { mutableStateOf("30") }
    var selectedDays by remember { mutableStateOf(DayOfWeek.entries.toSet()) }
    var query by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(ruleToEdit?.id) {
        installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        detectedHabits = detectedHabitManager.latest()
        ruleToEdit?.let { rule ->
            editingId = rule.id
            selectedApps = rule.targetPackageNames().toSet()
            val names = rule.requiredHabitNames()
            selectedHabits = names.toSet()
            val detectedNames = detectedHabits.map { it.name }.toSet()
            extraHabitNames = names.filterNot { it in detectedNames }
            val start = rule.windowStartMinute
            val end = rule.windowEndMinute
            if (start != null && end != null) {
                windowEnabled = true
                startText = formatMinuteOfDay(start)
                endText = formatMinuteOfDay(end)
            }
            selectedDays = decodeDaysOfWeek(rule.daysOfWeekMask)
            minutesText = (rule.unlockMinutes.takeIf { it > 0 } ?: 30).toString()
        }
        ready = true
    }

    val habitOptions = remember(detectedHabits, extraHabitNames) {
        (detectedHabits.map { it.name } + extraHabitNames).distinct()
    }
    val windowStart = if (windowEnabled) parseTimeToMinuteOfDay(startText) else null
    val windowEnd = if (windowEnabled) parseTimeToMinuteOfDay(endText) else null
    val minutes = minutesText.toIntOrNull()
    val title = if (editingId != null) "Edit Rule" else "Create Rule"

    val canAdvance = when (step) {
        1 -> selectedApps.isNotEmpty()
        3 -> if (windowEnabled) windowStart != null && windowEnd != null else (minutes != null && minutes > 0)
        else -> true
    }

    fun save() {
        val targets = selectedApps.toList()
        if (targets.isEmpty()) return
        val trigger = habitShareAppInfo(installedApps)
        val habitNames = habitOptions.filter { it in selectedHabits }
        val id = editingId
        coroutineScope.launch {
            val start = if (windowEnabled) windowStart else null
            val end = if (windowEnabled) windowEnd else null
            val unlockMinutes = if (windowEnabled) 0 else (minutes ?: 30)
            if (id == null) {
                ruleManager.addRule(
                    trigger.packageName, targets, unlockMinutes, habitNames, start, end, selectedDays,
                )
            } else {
                ruleManager.updateRule(
                    id, trigger.packageName, targets, unlockMinutes, habitNames, start, end, selectedDays,
                )
            }
            onSaved()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                    IconButton(
                        onClick = { if (step > 1) step-- else onDismiss() },
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Text(
                        "Step $step/3",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (!ready) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        when (step) {
                            1 -> WizardStepTarget(
                                apps = installedApps,
                                query = query,
                                onQueryChange = { query = it },
                                selectedPackages = selectedApps,
                                onToggle = { app ->
                                    selectedApps = if (app.packageName in selectedApps) {
                                        selectedApps - app.packageName
                                    } else {
                                        selectedApps + app.packageName
                                    }
                                },
                            )
                            2 -> WizardStepHabits(
                                habitOptions = habitOptions,
                                selected = selectedHabits,
                                onToggle = { name ->
                                    selectedHabits = if (name in selectedHabits) selectedHabits - name else selectedHabits + name
                                },
                            )
                            else -> WizardStepSchedule(
                                windowEnabled = windowEnabled,
                                onWindowEnabledChange = { windowEnabled = it },
                                startText = startText,
                                onStartChange = { startText = it },
                                endText = endText,
                                onEndChange = { endText = it },
                                minutesText = minutesText,
                                onMinutesChange = { minutesText = it },
                                selectedDays = selectedDays,
                                onToggleDay = { day ->
                                    val updated = if (day in selectedDays) selectedDays - day else selectedDays + day
                                    if (updated.isNotEmpty()) selectedDays = updated
                                },
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                        Button(
                            onClick = { if (step < 3) step++ else save() },
                            enabled = ready && canAdvance,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (step < 3) "Continue" else "Save Rule")
                        }
                    }
                }
            }
        }
    }
}

/** Step title + supporting subtitle shared by every wizard step. */
@Composable
private fun WizardHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Step 1: searchable installed-app list; tapping a row toggles it in/out of the target set. At
 * least one app must be selected to advance. */
@Composable
private fun WizardStepTarget(
    apps: List<InstalledAppInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedPackages: Set<String>,
    onToggle: (InstalledAppInfo) -> Unit,
) {
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        WizardHeading(
            "Which apps to block?",
            if (selectedPackages.isEmpty()) {
                "Select one or more target applications"
            } else {
                "${selectedPackages.size} app${if (selectedPackages.size == 1) "" else "s"} selected"
            },
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search apps...") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.packageName }) { app ->
                AppRow(app = app, selected = app.packageName in selectedPackages, onClick = { onToggle(app) })
            }
            if (filtered.isEmpty()) {
                item {
                    Text("No apps match your search.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** A single installed-app row: leading rounded icon badge + app label, with a trailing check when
 * selected. */
@Composable
private fun AppRow(app: InstalledAppInfo, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Step 2: wrap of habit pills; tapping toggles selection. Selecting none = time-only rule. */
@Composable
private fun WizardStepHabits(
    habitOptions: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        WizardHeading("Require habits", "Select habits that must be done")
        Spacer(Modifier.height(16.dp))
        if (habitOptions.isEmpty()) {
            Text(
                "No habits detected yet. Connect your HabitShare account (or open HabitShare) so " +
                    "your habits appear here. You can still continue to create a time-only rule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                habitOptions.forEach { name ->
                    val isSelected = name in selected
                    SelectablePill(
                        text = if (isSelected) "$name  ✓" else name,
                        selected = isSelected,
                        onClick = { onToggle(name) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "All selected habits must be done to unlock the app. Select none for a time-only rule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Clickable toggle pill mirroring the Figma habit chips: Success variant + ✓ when selected. */
@Composable
private fun SelectablePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Step 3: schedule card. The time-window toggle switches between the windowed path (unlockMinutes
 * = 0) and the unlock-duration path (window null, N minutes), keeping both rule modes reachable. */
@Composable
private fun WizardStepSchedule(
    windowEnabled: Boolean,
    onWindowEnabledChange: (Boolean) -> Unit,
    startText: String,
    onStartChange: (String) -> Unit,
    endText: String,
    onEndChange: (String) -> Unit,
    minutesText: String,
    onMinutesChange: (String) -> Unit,
    selectedDays: Set<DayOfWeek>,
    onToggleDay: (DayOfWeek) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        WizardHeading("When does this apply?", "Set the schedule for this rule")
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Time window", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = windowEnabled, onCheckedChange = onWindowEnabledChange)
                }
                if (windowEnabled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = onStartChange,
                            label = { Text("Start") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text("to")
                        OutlinedTextField(
                            value = endText,
                            onValueChange = onEndChange,
                            label = { Text("End") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (parseTimeToMinuteOfDay(startText) == null || parseTimeToMinuteOfDay(endText) == null) {
                        Text(
                            "Enter times as HH:mm, e.g. 21:00 (use 00:00 as the end for midnight).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = onMinutesChange,
                        label = { Text("Unlock duration (minutes)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "With no time window, the app unlocks for this many minutes once the required habits are done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text("Days of week", style = MaterialTheme.typography.titleSmall)
                DayOfWeekPicker(selectedDays = selectedDays, onToggle = onToggleDay)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Compact Mon-Sun toggle row for restricting a windowed rule to specific days -- at least one
 * day must stay selected (see [HabitWindowPickerDialog.toggleDay]). Rendered as a row of circular
 * toggle buttons matching the Figma design. */
@Composable
private fun DayOfWeekPicker(selectedDays: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in selectedDays
            Surface(
                onClick = { onToggle(day) },
                modifier = Modifier.weight(1f).aspectRatio(1f),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        day.name.take(1),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private fun parseTimeToMinuteOfDay(text: String): Int? {
    val match = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
