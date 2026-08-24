package app.otterling.tamper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.otterling.ui.theme.FamilyGuardTheme

/**
 * Shown after [app.otterling.monitoring.DeviceRemovalHandler] has already disabled every
 * protection and relinquished Device Owner -- this screen has nothing left to enforce, it's purely
 * informational plus a convenience shortcut. Deliberately NOT pinned/un-dismissable (unlike
 * [AccessibilityGuardActivity]): by the time this shows, there's no protection left to protect by
 * trapping the user here, and lock task mode would actively get in the way of reaching the system
 * uninstall confirmation this screen exists to point at.
 *
 * Android gives no fully-silent self-uninstall path for an app that isn't itself
 * system/privileged, even after Device Owner is cleared -- [Intent.ACTION_DELETE] is the real,
 * supported mechanism, and it always shows the OS's own confirmation dialog. That one required tap
 * is a platform floor, not a gap in this feature.
 */
class DeviceRemovedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FamilyGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeviceRemovedScreen(
                        onUninstall = {
                            startActivity(
                                Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                }
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, DeviceRemovedActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}

@Composable
private fun DeviceRemovedScreen(onUninstall: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val primary = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "This device was removed",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Your Guardian removed this device from the dashboard. All Otterling protections " +
                "have already been turned off. You can uninstall the app now, or do it later " +
                "from Settings → Apps.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onUninstall,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Uninstall Otterling", style = MaterialTheme.typography.titleMedium)
        }
    }
}
