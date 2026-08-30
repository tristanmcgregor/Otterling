package app.otterling.ui.nav

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.otterling.ui.AccessibilityServiceSection
import app.otterling.ui.AccountabilityPartnerSection
import app.otterling.ui.BlockedWebsitesSettingsSection
import app.otterling.ui.HabitRulesSection
import app.otterling.ui.HabitShareSettingsScreen
import app.otterling.ui.MacTamperAlertSection
import app.otterling.ui.MindfulAppsSection
import app.otterling.ui.TimeBudgetsSection
import app.otterling.ui.UpdateSection
import app.otterling.ui.VpnFilterSection
import app.otterling.ui.settings.DeviceOwnerSection
import app.otterling.ui.settings.DisabledAppsSection
import app.otterling.ui.settings.HabitShareNavSection
import app.otterling.ui.settings.KnoxSetupSection
import app.otterling.ui.settings.ProtectionControlSection
import app.otterling.ui.settings.RestrictionsSection
import app.otterling.ui.settings.UninstallProtectionSection

/** Top-level routes shown in the app shell's bottom navigation bar. */
private enum class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Filtering("filtering", "Filtering", Icons.Default.Language),
    Rules("rules", "Rules", Icons.Default.Shield),
    Accountability("accountability", "Accountability", Icons.Default.Group),
    Settings("settings", "Settings", Icons.Default.Settings),
}

private const val ROUTE_HABIT_SHARE = "habitshare"

/**
 * The PIN-gated shell replacing the old single scrolling Settings screen: a bottom-nav bar over
 * a NavHost, organized the way the web dashboard organizes a device's "Protect" screens (Blocked
 * Sites + Content Filter, Habit rules, Accountability, Settings), plus a nested full-screen
 * HabitShare settings route reachable from Settings.
 *
 * Purely a UI/navigation reorganization — every section composable here is the same one used
 * before, unchanged in behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    context: Context,
    batteryOptimizationLauncher: ActivityResultLauncher<Intent>,
    onExit: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val showBottomBar = Tab.entries.any { tab -> currentRoute?.hierarchy?.any { it.route == tab.route } == true }

    Scaffold(
        topBar = {
            // The HabitShare route draws its own header (see HabitShareSettingsScreen), so only
            // the four tab routes get this shared bar.
            if (showBottomBar) {
                TopAppBar(
                    title = {
                        Text(currentRoute?.route?.let { route -> Tab.entries.find { it.route == route }?.label } ?: "")
                    },
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Filtering.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Tab.Filtering.route) {
                ScrollingTabContent {
                    VpnFilterSection(context)
                    BlockedWebsitesSettingsSection(context)
                }
            }
            composable(Tab.Rules.route) {
                ScrollingTabContent {
                    AccessibilityServiceSection(context)
                    MindfulAppsSection(context)
                    TimeBudgetsSection(context)
                    HabitRulesSection(context)
                }
            }
            composable(Tab.Accountability.route) {
                ScrollingTabContent {
                    AccountabilityPartnerSection(context)
                    MacTamperAlertSection(context)
                }
            }
            composable(Tab.Settings.route) {
                ScrollingTabContent {
                    ProtectionControlSection(context)
                    DeviceOwnerSection(context)
                    RestrictionsSection(context, batteryOptimizationLauncher)
                    UninstallProtectionSection(context)
                    DisabledAppsSection(context)
                    HabitShareNavSection(onOpen = { navController.navigate(ROUTE_HABIT_SHARE) })
                    KnoxSetupSection(context)
                    UpdateSection(context)
                }
            }
            composable(ROUTE_HABIT_SHARE) {
                HabitShareSettingsScreen(context = context, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun ScrollingTabContent(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
