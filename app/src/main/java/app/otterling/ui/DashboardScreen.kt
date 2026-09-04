package app.otterling.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.otterling.admin.DeviceOwnerManager
import app.otterling.content.CustomBlocklistManager
import app.otterling.focus.AppTimeBudget
import app.otterling.focus.AppTimeBudgetManager
import app.otterling.focus.AppUsageCounter
import app.otterling.focus.DetectedHabit
import app.otterling.focus.DetectedHabitManager
import app.otterling.focus.HabitProofActivity
import app.otterling.focus.HabitProofLog
import app.otterling.focus.HabitProofManager
import app.otterling.focus.HabitProofRequirement
import app.otterling.focus.HabitRule
import app.otterling.focus.HabitRuleManager
import app.otterling.focus.HabitShareSyncManager
import app.otterling.focus.daysOfWeekSet
import app.otterling.focus.isTimeWindowed
import app.otterling.focus.requiredHabitNames
import app.otterling.focus.targetPackageNames
import app.otterling.monitoring.DebugLogReader
import app.otterling.monitoring.ProtectionController
import app.otterling.restrictions.DeviceRestrictionsManager
import app.otterling.restrictions.Restriction
import app.otterling.tamper.TamperEvent
import app.otterling.tamper.TamperEventLogger
import app.otterling.ui.components.FilledPillButton
import app.otterling.ui.components.OutlinedPillButton
import app.otterling.ui.components.StatTile
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
    var showMenu by remember { mutableStateOf(false) }
    var showDebugLogs by remember { mutableStateOf(false) }
    var showCheckUpdates by remember { mutableStateOf(false) }
    var habitsRefreshing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastKnownDay by remember { mutableStateOf(LocalDate.now()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh) {
        loadError = null
        runCatching { withContext(Dispatchers.IO) { loadDashboardData(context) } }
            .onSuccess { data = it }
            .onFailure { loadError = "Couldn't load the dashboard. Tap refresh to try again." }
    }
    // Reloads the dashboard the moment the calendar day rolls over, so "Done" statuses that only
    // hold until midnight (see habitStatus's dateEpochDay check) don't keep showing yesterday's
    // completions on a dashboard screen that's just been sitting open since before midnight.
    LaunchedEffect(Unit) {
        while (isActive) {
            now = System.currentTimeMillis()
            val today = LocalDate.now()
            if (today != lastKnownDay) {
                lastKnownDay = today
                refresh++
            }
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
    if (showDebugLogs) {
        DebugLogsDialog(onDismiss = { showDebugLogs = false })
    }
    if (showCheckUpdates) {
        CheckForUpdatesDialog(context = context, onDismiss = { showCheckUpdates = false })
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Otterling",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Your protection and progress today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Check for updates") },
                            onClick = {
                                showMenu = false
                                showCheckUpdates = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Debug logs") },
                            onClick = {
                                showMenu = false
                                showDebugLogs = true
                            },
                        )
                    }
                }
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
                    ProtectionStatus(context, snapshot) { refresh++ }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile(
                            value = snapshot.rules.count { it.enabled }.toString(),
                            label = "Active rules",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            value = snapshot.budgets.size.toString(),
                            label = "App budgets",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledPillButton(
                            text = "Add rule",
                            onClick = { showRuleWizard = true },
                            icon = Icons.Default.Add,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedPillButton(
                            text = "Block web",
                            onClick = { showDomainDialog = true },
                            icon = Icons.Default.Language,
                            modifier = Modifier.weight(1f),
                        )
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
                }
            }

            Spacer(Modifier.height(72.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            FilledPillButton(
                text = "Open Settings with PIN",
                onClick = onOpenSettings,
                icon = Icons.Default.Lock,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun ProtectionStatus(context: Context, data: DashboardData, onRestored: () -> Unit) {
    val good = data.isDeviceOwner
    val someOff = data.isDeviceOwner && data.activeRestrictions < data.totalRestrictions
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    val container = if (good) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    val badge = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    val onBadge = if (good) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onError
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = onContainer,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(badge),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = onBadge)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (good) "Protected" else "Setup required",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (good) {
                            "${data.activeRestrictions} of ${data.totalRestrictions} tamper protections active"
                        } else {
                            "Device Owner isn't active. Open Settings to finish setup."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.8f),
                    )
                }
            }
            // No-PIN escape hatch: only ever restores what's already configured -- it can turn a
            // drifted-off protection back on but has no path to turn anything off, so it's safe to
            // expose without the Settings PIN. Two steps, in order:
            //  1. DeviceRestrictionsManager.detectDriftAndReapply, so a tamper-caused drift still
            //     fires its RESTRICTION_DRIFT alert to the accountability partner, exactly as it
            //     would from the periodic checker.
            //  2. ProtectionController.startup(), because "protection off" is often the *master*
            //     switch (Settings' "Turn protection on/off" -- itself PIN-gated), not just a
            //     drifted restriction. Without this second step the button would silently only
            //     restore the tamper restrictions counted in someOff below, leaving the VPN filter,
            //     habit rule enforcement, time budgets, and app suspensions off with no way to fix
            //     that short of the PIN this button exists to route around.
            if (someOff) {
                Button(
                    onClick = {
                        restoring = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    DeviceRestrictionsManager(context)
                                        .detectDriftAndReapply(TamperEventLogger(context))
                                }
                                runCatching { ProtectionController(context).startup() }
                            }
                            restoring = false
                            onRestored()
                        }
                    },
                    enabled = !restoring,
                ) {
                    Text(if (restoring) "Restoring…" else "Turn protection back on")
                }
            }
        }
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
    SectionCard(
        title = "Rules overview",
        icon = Icons.Default.LockClock,
        action = {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh habits")
                }
            }
        },
    ) {
        if (data.rules.isEmpty()) {
            Text("No habit rules yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            data.rules.forEach { rule -> RuleCard(context, data, rule, now) }
        }
    }
}

@Composable
private fun RuleCard(context: Context, data: DashboardData, rule: HabitRule, now: Long) {
    val required = rule.requiredHabitNames()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        rule.targetPackageNames().joinToString(", ") { data.label(it) },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (rule.isTimeWindowed()) {
                    Pill(
                        "${formatDays(rule.daysOfWeekSet())}, " +
                            "${formatMinute(rule.windowStartMinute!!)}–${formatMinute(rule.windowEndMinute!!)}",
                        PillVariant.Default,
                    )
                }
            }

            if (!rule.enabled) {
                Text(
                    "Disabled in Settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (required.isEmpty()) {
                if (rule.isTimeWindowed()) {
                    Text(
                        "Unconditional block during the window",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("All habits complete", style = MaterialTheme.typography.bodyMedium)
                        val done = allDetectedHabitsSatisfied(data)
                        Pill(
                            if (done) "Complete" else "Waiting",
                            if (done) PillVariant.Success else PillVariant.Warning,
                        )
                    }
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
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Pill(status, pillForStatus(status))
                            if (status == "Done, proof pending") {
                                FilledTonalButton(
                                    onClick = { HabitProofActivity.launch(context, name) },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                ) {
                                    Text("Verify", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }

            if (rule.isTimeWindowed()) {
                if (requiredHabitsAllDone(required, data)) {
                    RuleFooter(
                        icon = Icons.Default.CheckCircle,
                        text = "Unlocked",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    RuleFooter(
                        icon = Icons.Default.Lock,
                        text = "Blocked until requirements met",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            } else {
                val remaining = (rule.unlockUntilMillis - now).coerceAtLeast(0)
                if (remaining > 0) {
                    RuleFooter(
                        icon = Icons.Default.Schedule,
                        text = "Unlocked for ${formatDuration(remaining)} more",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    RuleFooter(
                        icon = Icons.Default.Lock,
                        text = "Unlocks for ${rule.unlockMinutes} min once done",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleFooter(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

private fun requiredHabitsAllDone(required: List<String>, data: DashboardData): Boolean =
    required.isNotEmpty() && required.all {
        val s = habitStatus(it, data)
        s == "Done and verified" || s == "Done"
    }

private fun pillForStatus(status: String): PillVariant = when (status) {
    "Not done" -> PillVariant.Warning
    "Done and verified", "Done" -> PillVariant.Success
    "Done, proof pending" -> PillVariant.Default
    else -> PillVariant.Default
}

@Composable
private fun TimeBudgetOverview(data: DashboardData) {
    SectionCard(title = "Remaining app time", icon = Icons.Default.History) {
        if (data.budgets.isEmpty()) {
            Text("No daily app budgets configured.", style = MaterialTheme.typography.bodySmall)
        } else {
            data.budgets.forEach { (budget, counter) ->
                val limitSeconds = budget.dailyLimitMinutes * 60
                val remainingSeconds = (limitSeconds - counter.totalSeconds).coerceAtLeast(0)
                val remainingFraction = (remainingSeconds.toFloat() / limitSeconds.coerceAtLeast(1))
                val low = remainingFraction <= 0.2f
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(data.label(budget.packageName), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${formatSeconds(remainingSeconds)} / ${budget.dailyLimitMinutes}m",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    TimeBudgetBar(fraction = remainingFraction, low = low)
                    budget.subLimitMinutes?.let { subLimit ->
                        val subRemaining = (subLimit * 60 - counter.subSeconds).coerceAtLeast(0)
                        Text(
                            "${budget.subLimitLabel?.takeIf { it.isNotBlank() } ?: "Sub-limit"}: " +
                                "${formatSeconds(subRemaining)} remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugLogsDialog(onDismiss: () -> Unit) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Text("Debug logs")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        },
        dismissButton = {
            TextButton(onClick = { load() }, enabled = !loading) { Text("Refresh") }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
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
        subtitle = "Domains are blocked by the VPN (including subdomains). Paths like " +
            "youtube.com/shorts are blocked in browsers and the YouTube app without blocking the rest of the site.",
    ) {
        Button(onClick = { showAdd = true }) { Text("Add website") }
        if (domains.isEmpty()) {
            Text("No custom websites blocked.", style = MaterialTheme.typography.bodySmall)
        } else {
            // Dashboard-sourced entries can't be removed from here -- same reasoning as
            // VpnFilterSection's MitmExemptManager.dashboardExemptPackages check: remove() only
            // ever touches local storage, so a working-looking Remove button here would silently
            // do nothing (it'd reappear on the next domains()/entries() merge).
            val dashboardManaged = remember(domains) { manager.dashboardEntries().map { it.display() }.toSet() }
            domains.forEach { domain ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(domain)
                        if ('/' in domain) {
                            Text(
                                "Path rule (browser / YouTube app)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (domain in dashboardManaged) {
                        Text("Managed by dashboard", style = MaterialTheme.typography.bodySmall)
                    } else {
                        TextButton(onClick = {
                            manager.remove(domain)
                            domains = manager.domains()
                        }) { Text("Remove") }
                    }
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
                Text(
                    "Enter a domain (example.com) or a path (youtube.com/shorts). " +
                        "Paths block only that URL prefix — the rest of the site stays available.",
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("example.com or youtube.com/shorts") },
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
            (if (restrictions.isUninstallBlocked()) 1 else 0) +
            (if (restrictions.isChromeIncognitoBlocked()) 1 else 0),
        totalRestrictions = Restriction.entries.size + 2,
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

private fun DashboardData.label(packageName: String): String = appLabels[packageName] ?: prettyPackageName(packageName)

/** Best-effort human-readable fallback for a target app that isn't installed on this device (so
 *  its real launcher label can't be looked up) -- e.g. "com.google.android.youtube" -> "Youtube",
 *  rather than showing the raw dotted package id. Package-visible: also used by
 *  [HabitRulesSection]'s equivalent fallback. */
internal fun prettyPackageName(packageName: String): String {
    val lastSegment = packageName.substringAfterLast('.').ifBlank { packageName }
    return lastSegment.split('_', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        .ifBlank { packageName }
}

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
    if (!proofRequired) return "Done"
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
