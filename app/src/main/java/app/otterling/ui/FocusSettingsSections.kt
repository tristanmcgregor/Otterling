package app.otterling.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Button
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
import app.otterling.focus.AppTimeBudget
import app.otterling.focus.AppTimeBudgetManager
import app.otterling.focus.MindfulApp
import app.otterling.focus.MindfulAppManager
import app.otterling.restrictions.AccessibilityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prerequisite for every feature below: friction screens, time budgets, and habit detection. If
 * this ever gets turned off, [app.otterling.tamper.AccessibilityGuardActivity]
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
    val dashboardManaged = remember(refreshTrigger) { budgetManager.dashboardManagedPackages() }

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
                    if (budget.packageName in dashboardManaged) {
                        Text("Managed by dashboard", style = MaterialTheme.typography.bodySmall)
                    } else {
                        TextButton(onClick = {
                            coroutineScope.launch {
                                budgetManager.removeBudget(budget.packageName)
                                refreshTrigger++
                            }
                        }) {
                            Text("Remove")
                        }
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
