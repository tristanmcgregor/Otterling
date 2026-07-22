package au.com.tbmcgregor.bwparker.familyguard.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
)

/**
 * Returns installed apps (excluding this app), sorted by display name. Call this off the main
 * thread. Deliberately does NOT require a launcher icon/intent -- apps like Accountable2You
 * commonly hide their icon so kids can't easily find and remove them, so filtering by
 * [PackageManager.getLaunchIntentForPackage] would hide exactly the apps a parent most wants to
 * find here. As device owner this app can see all installed packages regardless of Android's
 * normal package-visibility filtering. Pure factory system apps (no user-visible purpose, never
 * updated) are excluded to keep the list manageable; pre-installed apps that have since been
 * updated (Chrome, Play Store, etc.) are kept.
 */
fun loadInstalledApps(context: Context): List<InstalledAppInfo> {
    val packageManager = context.packageManager
    val ownPackage = context.packageName
    return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        .asSequence()
        .filter { it.packageName != ownPackage }
        .filter { app ->
            val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val wasUpdated = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystemApp || wasUpdated
        }
        .map { InstalledAppInfo(it.packageName, it.loadLabel(packageManager).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@Composable
fun AppPickerDialog(
    apps: List<InstalledAppInfo>,
    initialQuery: String = "",
    onDismiss: () -> Unit,
    onSelect: (InstalledAppInfo) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(app) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text("No apps match your search.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
