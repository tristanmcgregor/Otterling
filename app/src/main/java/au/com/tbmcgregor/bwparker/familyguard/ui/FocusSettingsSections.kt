package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import au.com.tbmcgregor.bwparker.familyguard.focus.FocusGuardAccessibilityService
import au.com.tbmcgregor.bwparker.familyguard.focus.FocusSessionManager
import au.com.tbmcgregor.bwparker.familyguard.focus.HabitGateManager
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulApp
import au.com.tbmcgregor.bwparker.familyguard.focus.MindfulAppManager
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardApp
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardAppManager
import au.com.tbmcgregor.bwparker.familyguard.focus.RewardLedgerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
fun FocusRewardsSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val sessionManager = remember { FocusSessionManager(context) }
    val ledgerManager = remember { RewardLedgerManager(context) }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var earnedMinutes by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(sessionManager.activeSession()) }
    var remainingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        earnedMinutes = ledgerManager.earnedMinutes()
        active = sessionManager.activeSession()
    }

    LaunchedEffect(active) {
        val current = active ?: return@LaunchedEffect
        while (true) {
            val remainingMillis = current.endsAtMillis() - System.currentTimeMillis()
            remainingSeconds = (remainingMillis / 1000).coerceAtLeast(0).toInt()
            if (remainingMillis <= 0) {
                sessionManager.complete()
                refreshTrigger++
                break
            }
            delay(1000)
        }
    }

    SectionCard(
        title = "Focus Sessions & Rewards",
        icon = Icons.Default.SelfImprovement,
        subtitle = "Finish a focus session or today's habits to earn minutes, then spend them " +
            "to unlock your \"Reward apps\" below (e.g. YouTube, Instagram).",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Reward minutes banked", style = MaterialTheme.typography.bodyLarge)
            Text("$earnedMinutes min", style = MaterialTheme.typography.bodyLarge)
        }

        val currentSession = active
        if (currentSession != null) {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            Text(
                "Focus session running: %d:%02d remaining".format(minutes, seconds),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = {
                coroutineScope.launch {
                    sessionManager.cancelActive()
                    refreshTrigger++
                }
            }) {
                Text("Cancel (forfeit reward)")
            }
        } else {
            Text("Start a focus session:", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45).forEach { minutes ->
                    OutlinedButton(onClick = {
                        coroutineScope.launch {
                            sessionManager.start(minutes)
                            refreshTrigger++
                        }
                    }) {
                        Text("$minutes min")
                    }
                }
            }
        }

        HorizontalDivider()

        Text("Spend banked minutes now:", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30, 60).forEach { minutes ->
                OutlinedButton(
                    enabled = earnedMinutes >= minutes,
                    onClick = {
                        coroutineScope.launch {
                            ledgerManager.spend(minutes)
                            refreshTrigger++
                        }
                    },
                ) {
                    Text("$minutes min")
                }
            }
        }
        if (ledgerManager.isCurrentlyUnlocked()) {
            val remainingMin = ((ledgerManager.activeUnlockUntilMillis() - System.currentTimeMillis()) / 60_000L)
                .coerceAtLeast(0)
            Text("Reward apps unlocked for about $remainingMin more min.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RewardAppsSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val rewardAppManager = remember { RewardAppManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<RewardApp>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showAppPicker by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) { apps = rewardAppManager.rewardApps() }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                coroutineScope.launch {
                    rewardAppManager.add(app.packageName)
                    refreshTrigger++
                }
                showAppPicker = false
            },
        )
    }

    SectionCard(
        title = "Reward Apps",
        icon = Icons.Default.HourglassTop,
        subtitle = "Suspended by default. Only open while you're spending banked reward minutes above.",
    ) {
        Button(onClick = {
            coroutineScope.launch {
                installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                showAppPicker = true
            }
        }) {
            Text("Choose app to gate behind rewards")
        }
        if (apps.isEmpty()) {
            Text("No reward apps configured yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            apps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.packageName, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        coroutineScope.launch {
                            rewardAppManager.remove(app.packageName)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                coroutineScope.launch {
                    budgetManager.setBudget(
                        packageName = "com.google.android.youtube",
                        dailyLimitMinutes = 120,
                        subLimitMinutes = 60,
                        subLimitLabel = "Shorts",
                    )
                    refreshTrigger++
                }
            }) {
                Text("Quick add: YouTube (2h / 1h Shorts)")
            }
            Button(onClick = {
                coroutineScope.launch {
                    installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                    showAppPicker = true
                }
            }) {
                Text("Choose app + custom limits")
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
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
fun HabitGateSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val habitGateManager = remember { HabitGateManager(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var trackerPackage by remember { mutableStateOf(habitGateManager.trackerPackageName) }
    var rewardMinutesText by remember { mutableStateOf(habitGateManager.rewardMinutes.toString()) }
    var grantedToday by remember { mutableStateOf(false) }
    var capturedText by remember { mutableStateOf("") }
    var showCapture by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        grantedToday = habitGateManager.isGrantedToday()
        capturedText = habitGateManager.lastCapturedText
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                habitGateManager.trackerPackageName = app.packageName
                trackerPackage = app.packageName
                showAppPicker = false
            },
        )
    }

    SectionCard(
        title = "Habit Tracker Reward Gate",
        icon = Icons.Default.CheckCircle,
        subtitle = "There's no public API for most habit trackers (e.g. HabitShare), so this " +
            "detects a \"done\" state by scanning on-screen text for a pattern like \"3/3\" -- " +
            "tune it using the capture below if it doesn't fire reliably.",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Habit tracker app", style = MaterialTheme.typography.bodyLarge)
            Text(trackerPackage ?: "Not set", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            coroutineScope.launch {
                installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                showAppPicker = true
            }
        }) {
            Text("Choose habit tracker app")
        }

        OutlinedTextField(
            value = rewardMinutesText,
            onValueChange = {
                rewardMinutesText = it
                it.toIntOrNull()?.let { minutes -> habitGateManager.rewardMinutes = minutes }
            },
            label = { Text("Reward minutes per day, once all habits are done") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Today's status", style = MaterialTheme.typography.bodyLarge)
            StatusText(if (grantedToday) "Reward granted" else "Not yet", isGood = grantedToday)
        }

        HorizontalDivider()

        TextButton(onClick = { showCapture = !showCapture }) {
            Text(if (showCapture) "Hide debug capture" else "Show debug capture (tuning helper)")
        }
        if (showCapture) {
            Text(
                "Last screen text read from the tracker app (open it, then come back here and " +
                    "refresh):",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                capturedText.ifBlank { "Nothing captured yet -- open the habit tracker app first." },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { refreshTrigger++ }) {
                Text("Refresh capture")
            }
        }
    }
}
