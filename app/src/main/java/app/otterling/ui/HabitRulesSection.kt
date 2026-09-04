package app.otterling.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.otterling.focus.DetectedHabit
import app.otterling.focus.DetectedHabitManager
import app.otterling.focus.HabitProofLog
import app.otterling.focus.HabitProofManager
import app.otterling.focus.HabitProofRequirement
import app.otterling.focus.HabitRule
import app.otterling.focus.HabitRuleManager
import app.otterling.focus.HabitShareSyncManager
import app.otterling.focus.daysOfWeekSet
import app.otterling.focus.requiredHabitNames
import app.otterling.focus.targetPackageNames
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var wizardOpen by remember { mutableStateOf(false) }
    var wizardRule by remember { mutableStateOf<HabitRule?>(null) }
    var showDetected by remember { mutableStateOf(false) }
    var showProofLog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        isRefreshing = true
        withContext(Dispatchers.IO) { runCatching { HabitShareSyncManager(context).syncIfConnected() } }
        rules = habitRuleManager.rules()
        detectedHabits = detectedHabitManager.latest()
        proofRequirements = habitProofManager.requirements()
        if (installedApps.isEmpty()) {
            installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        }
        isRefreshing = false
    }

    /** Falls back to a human-readable guess derived from the package id if the app list hasn't
     * loaded yet or it's not installed on this device. */
    fun appLabel(packageName: String): String =
        installedApps.find { it.packageName == packageName }?.label ?: prettyPackageName(packageName)

    if (wizardOpen) {
        HabitRuleWizardHost(
            context = context,
            ruleToEdit = wizardRule,
            onDismiss = { wizardOpen = false },
            onSaved = {
                wizardOpen = false
                refreshTrigger++
            },
        )
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
            wizardRule = null
            wizardOpen = true
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
                        targetLabel = rule.targetPackageNames().joinToString(", ") { appLabel(it) },
                        now = now,
                        dashboardManaged = habitRuleManager.isDashboardManaged(rule),
                        onEdit = {
                            wizardRule = rule
                            wizardOpen = true
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
            OutlinedButton(onClick = { refreshTrigger++ }, enabled = !isRefreshing) {
                Text(if (isRefreshing) "Refreshing…" else "Refresh")
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
    dashboardManaged: Boolean,
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
                if (dashboardManaged) {
                    Text("Managed by dashboard", style = MaterialTheme.typography.bodySmall)
                } else {
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
}

/** "Every day" if all 7 are selected, otherwise a short comma-joined list, e.g. "Mon, Wed, Fri". */
private fun formatDaysOfWeek(days: Set<DayOfWeek>): String {
    if (days.size == DayOfWeek.entries.size) return "every day"
    return DayOfWeek.entries.filter { it in days }
        .joinToString(", ") { it.name.take(1) + it.name.drop(1).take(2).lowercase() }
}

/** Package-visible (not private): also called from [HabitRuleWizardHost] in HabitRuleWizard.kt to
 * pre-fill the schedule step when editing an existing rule. */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
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
