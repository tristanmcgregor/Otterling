package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceOwnerManager
import au.com.tbmcgregor.bwparker.familyguard.content.CustomBlocklistManager
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudget
import au.com.tbmcgregor.bwparker.familyguard.focus.AppTimeBudgetManager
import au.com.tbmcgregor.bwparker.familyguard.focus.AppUsageCounter
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabit
import au.com.tbmcgregor.bwparker.familyguard.focus.DetectedHabitManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitProofActivity
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitProofLog
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitProofManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitProofRequirement
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRule
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitRuleManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitShareSyncManager
import au.com.tbmcgregor.bwparker.familyguard.focus.daysOfWeekSet
import au.com.tbmcgregor.bwparker.familyguard.focus.isTimeWindowed
import au.com.tbmcgregor.bwparker.familyguard.focus.requiredHabitNames
import au.com.tbmcgregor.bwparker.familyguard.monitoring.DebugLogReader
import au.com.tbmcgregor.bwparker.familyguard.restrictions.DeviceRestrictionsManager
import au.com.tbmcgregor.bwparker.familyguard.restrictions.Restriction
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEvent
import au.com.tbmcgregor.bwparker.familyguard.tamper.TamperEventLogger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DashboardData(
    val isDeviceOwner: Boolean,
    val activeRestrictions: Int,
    val totalRestrictions: Int,
    val rules: List<HabitRule>,
    val habits: List<DetectedHabit>,
    val requirements: List<HabitProofRequirement>,
    val proofToday: Set<String>,
    val proofLogs: List<HabitProofLog>,
    val budgets: List<Pair<AppTimeBudget, AppUsageCounter>>,
    val events: List<TamperEvent>,
    val appLabels: Map<String, String>,
)

@Composable
fun DashboardScreen(context: Context, onOpenSettings: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showRuleWizard by remember { mutableStateOf(false) }
    var showDomainDialog by remember { mutableStateOf(false) }
    var habitsRefreshing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh) {
        loadError = null
        runCatching { withContext(Dispatchers.IO) { loadDashboardData(context) } }
            .onSuccess { data = it }
            .onFailure { loadError = "Couldn't load the dashboard. Tap refresh to try again." }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    if (showRuleWizard) {
        HabitRuleWizardHost(
            context = context,
            onDismiss = { showRuleWizard = false },
            onSaved = {
                showRuleWizard = false
                refresh++
            },
        )
    }
    if (showDomainDialog) {
        AddBlockedWebsiteDialog(
            manager = remember { CustomBlocklistManager(context) },
            onDismiss = { showDomainDialog = false },
            onAdded = {
                showDomainDialog = false
                refresh++
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Family Device Guard", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your protection and progress today",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            data == null && loadError == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            loadError != null -> {
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
            }
            data != null -> {
                val snapshot = data!!
                ProtectionStatus(snapshot)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showRuleWizard = true }, modifier = Modifier.weight(1f)) {
                        Text("Add rule")
                    }
                    OutlinedButton(onClick = { showDomainDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("Block website")
                    }
                }
                RulesOverview(context, snapshot, now, habitsRefreshing) {
                    habitsRefreshing = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { HabitShareSyncManager(context).syncIfConnected() }
                        }
                        habitsRefreshing = false
                        refresh++
                    }
                }
                TimeBudgetOverview(snapshot)
                DebugLogs()
            }
        }

        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open Settings with PIN")
        }
    }
}

@Composable
private fun ProtectionStatus(data: DashboardData) {
    SectionCard(
        title = if (data.isDeviceOwner) "Protected" else "Setup required",
        icon = Icons.Default.Shield,
    ) {
        Text(
            if (data.isDeviceOwner) {
                "${data.activeRestrictions} of ${data.totalRestrictions} tamper protections active"
            } else {
                "Device Owner isn't active. Open Settings to finish setup."
            },
        )
    }
}

@Composable
private fun RulesOverview(
    context: Context,
    data: DashboardData,
    now: Long,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    SectionCard(title = "Rules overview", icon = Icons.Default.LockClock) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh habits")
                }
            }
        }
        if (data.rules.isEmpty()) {
            Text("No habit rules yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            data.rules.forEachIndexed { index, rule ->
                if (index > 0) HorizontalDivider()
                val required = rule.requiredHabitNames()
                Text(data.label(rule.targetPackageName), style = MaterialTheme.typography.titleSmall)
                if (!rule.enabled) {
                    Text("Disabled in Settings", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (required.isEmpty()) {
                    Text(
                        if (rule.isTimeWindowed()) {
                            "Unconditional block during the window"
                        } else {
                            "Required: all habits complete"
                        },
                    )
                    if (!rule.isTimeWindowed()) {
                        Text(
                            if (allDetectedHabitsSatisfied(data)) {
                                "Status: all detected habits complete"
                            } else {
                                "Status: waiting for all habits"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    required.forEach { name ->
                        val status = habitStatus(name, data)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "• $name — $status",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (status == "Done, proof pending") {
                                TextButton(onClick = { HabitProofActivity.launch(context, name) }) {
                                    Text("Verify")
                                }
                            }
                        }
                    }
                }
                if (rule.isTimeWindowed()) {
                    Text(
                        "${formatDays(rule.daysOfWeekSet())}, " +
                            "${formatMinute(rule.windowStartMinute!!)}–${formatMinute(rule.windowEndMinute!!)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val remaining = (rule.unlockUntilMillis - now).coerceAtLeast(0)
                    Text(
                        if (remaining > 0) {
                            "Unlocked for ${formatDuration(remaining)} more"
                        } else {
                            "Unlock duration: ${rule.unlockMinutes} minutes"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeBudgetOverview(data: DashboardData) {
    SectionCard(title = "Remaining app time", icon = Icons.Default.History) {
        if (data.budgets.isEmpty()) {
            Text("No daily app budgets configured.", style = MaterialTheme.typography.bodySmall)
        } else {
            data.budgets.forEachIndexed { index, (budget, counter) ->
                if (index > 0) HorizontalDivider()
                val limitSeconds = budget.dailyLimitMinutes * 60
                val remainingSeconds = (limitSeconds - counter.totalSeconds).coerceAtLeast(0)
                Text(data.label(budget.packageName), style = MaterialTheme.typography.titleSmall)
                Text("${formatSeconds(remainingSeconds)} remaining of ${budget.dailyLimitMinutes} min")
                LinearProgressIndicator(
                    progress = { (counter.totalSeconds.toFloat() / limitSeconds.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                budget.subLimitMinutes?.let { subLimit ->
                    val subRemaining = (subLimit * 60 - counter.subSeconds).coerceAtLeast(0)
                    Text(
                        "${budget.subLimitLabel?.takeIf { it.isNotBlank() } ?: "Sub-limit"}: " +
                            "${formatSeconds(subRemaining)} remaining",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugLogs() {
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        scope.launch {
            lines = DebugLogReader.recentLines()
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    SectionCard(title = "Debug logs", icon = Icons.Default.History) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = { load() }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh logs")
                }
            }
        }
        val current = lines
        when {
            current == null -> Text("Loading logs…", style = MaterialTheme.typography.bodySmall)
            current.isEmpty() -> Text("No logs captured yet.", style = MaterialTheme.typography.bodySmall)
            else -> {
                val reversed = current.asReversed()
                val visible = if (expanded) reversed.take(200) else reversed.take(DEBUG_LOG_COLLAPSED_LIMIT)
                visible.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (reversed.size > DEBUG_LOG_COLLAPSED_LIMIT) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show less" else "Show more")
                    }
                }
            }
        }
    }
}

private const val DEBUG_LOG_COLLAPSED_LIMIT = 8

@Composable
fun BlockedWebsitesSettingsSection(context: Context) {
    val manager = remember { CustomBlocklistManager(context) }
    var domains by remember { mutableStateOf(manager.domains()) }
    var showAdd by remember { mutableStateOf(false) }
    if (showAdd) {
        AddBlockedWebsiteDialog(
            manager = manager,
            onDismiss = { showAdd = false },
            onAdded = {
                domains = manager.domains()
                showAdd = false
            },
        )
    }
    SectionCard(
        title = "Blocked Websites",
        icon = Icons.Default.Language,
        subtitle = "Custom domains are blocked immediately in the running VPN, including subdomains.",
    ) {
        Button(onClick = { showAdd = true }) { Text("Add website") }
        if (domains.isEmpty()) {
            Text("No custom websites blocked.", style = MaterialTheme.typography.bodySmall)
        } else {
            domains.forEach { domain ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(domain, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        manager.remove(domain)
                        domains = manager.domains()
                    }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun AddBlockedWebsiteDialog(
    manager: CustomBlocklistManager,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block website") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a domain. Its subdomains will also be blocked.")
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("example.com") },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    manager.add(input)
                        .onSuccess { onAdded() }
                        .onFailure { error = it.message ?: "Enter a valid domain." }
                },
            ) { Text("Block") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private suspend fun loadDashboardData(context: Context): DashboardData {
    val ownerManager = DeviceOwnerManager(context)
    val restrictions = DeviceRestrictionsManager(context)
    val ruleManager = HabitRuleManager(context)
    val habitManager = DetectedHabitManager(context)
    val proofManager = HabitProofManager(context)
    val budgetManager = AppTimeBudgetManager(context)
    val rules = ruleManager.rules()
    val habits = habitManager.latest()
    val requirements = proofManager.requirements()
    val proofToday = requirements
        .filter { proofManager.hasProofToday(it.habitName) }
        .map { it.habitName.lowercase() }
        .toSet()
    val budgets = budgetManager.budgets().map { it to budgetManager.todayCounter(it.packageName) }
    val labels = loadInstalledApps(context).associate { it.packageName to it.label }
    return DashboardData(
        isDeviceOwner = ownerManager.currentStatus().isDeviceOwner,
        activeRestrictions = Restriction.entries.count { restrictions.isEnabled(it) } +
            if (restrictions.isUninstallBlocked()) 1 else 0,
        totalRestrictions = Restriction.entries.size + 1,
        rules = rules,
        habits = habits,
        requirements = requirements,
        proofToday = proofToday,
        proofLogs = proofManager.recentLogs(),
        budgets = budgets,
        events = TamperEventLogger(context).recent(50),
        appLabels = labels,
    )
}

private fun DashboardData.label(packageName: String): String = appLabels[packageName] ?: packageName

private fun habitStatus(
    name: String,
    data: DashboardData,
    exactHabit: DetectedHabit? = null,
): String {
    val today = LocalDate.now().toEpochDay()
    val habit = exactHabit ?: data.habits.find { detected ->
        detected.name.equals(name, ignoreCase = true) ||
            detected.name.contains(name, ignoreCase = true) ||
            name.contains(detected.name, ignoreCase = true)
    }
    val done = habit?.doneToday == true && habit.dateEpochDay == today
    if (!done) return "Not done"
    val proofRequired = data.requirements.any {
        it.habitName.equals(habit.name, ignoreCase = true) &&
            it.required && !it.referencePhotoPath.isNullOrBlank()
    }
    if (!proofRequired) return "Done (no proof needed)"
    return if (habit.name.lowercase() in data.proofToday) {
        "Done and verified"
    } else {
        "Done, proof pending"
    }
}

private fun allDetectedHabitsSatisfied(data: DashboardData): Boolean {
    val today = LocalDate.now().toEpochDay()
    val currentHabits = data.habits.filter { it.dateEpochDay == today }
    return currentHabits.isNotEmpty() && currentHabits.all {
        it.doneToday && habitStatus(it.name, data, it) != "Done, proof pending"
    }
}

private fun formatMinute(value: Int): String = "%02d:%02d".format(value / 60, value % 60)

private fun formatDays(days: Set<DayOfWeek>): String =
    if (days.size == DayOfWeek.entries.size) "Every day"
    else days.joinToString { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${remainingSeconds}s"
}

private fun formatSeconds(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
