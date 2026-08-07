package app.otterling.tamper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.otterling.restrictions.AccessibilityGuard
import app.otterling.ui.theme.FamilyGuardTheme
import kotlinx.coroutines.delay

/**
 * Full-screen, un-dismissable nag shown whenever [AccessibilityGuard.isEnabled] is found false
 * (see `ProtectionEnforcementService`/`RestrictionEnforcementWorker`). Swallows back presses and
 * pins itself via Lock Task Mode -- as device owner this enters pinning directly, no user
 * confirmation dialog -- so Home/Recents can't be used to escape it either. Polls every 1.5s and
 * releases itself (unpins, finishes) the moment the accessibility service is back on; there's no
 * other way to dismiss it, by design, mirroring [app.otterling.focus.FrictionActivity].
 */
class AccessibilityGuardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        runCatching { startLockTask() }

        setContent {
            FamilyGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AccessibilityGuardScreen(
                        onOpenSettings = {
                            startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        onResolved = {
                            runCatching { stopLockTask() }
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // A config change (e.g. rotation) destroys and immediately recreates this activity --
        // `isChangingConfigurations` is true for that case. Without this guard, `isShowing`
        // would briefly read false in the gap between this onDestroy() and the next onCreate(),
        // which a concurrently-polling caller (ProtectionEnforcementService's periodic check)
        // could observe and launch a redundant duplicate on top of the one already recreating.
        if (!isChangingConfigurations) {
            isShowing = false
        }
        super.onDestroy()
    }

    companion object {
        @Volatile private var isShowing = false

        /** Safe to call repeatedly (e.g. from a polling loop) -- no-ops while already showing so
         * a fast-firing observer/poll can't stack duplicate pinned activities. */
        fun launch(context: Context) {
            if (isShowing) return
            context.startActivity(
                Intent(context, AccessibilityGuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}

@Composable
private fun AccessibilityGuardScreen(onOpenSettings: () -> Unit, onResolved: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            if (AccessibilityGuard.isEnabled(context)) {
                onResolved()
                break
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.error)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val onError = MaterialTheme.colorScheme.onError
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(onError.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = onError,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Protection Disabled",
            style = MaterialTheme.typography.headlineMedium,
            color = onError,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "The required accessibility service was turned off. Otterling can't enforce " +
                "friction screens, time budgets, or habit-based unlocks until it's back on.",
            style = MaterialTheme.typography.bodyLarge,
            color = onError.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = onError.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, onError.copy(alpha = 0.25f)),
            contentColor = onError,
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How to fix:", style = MaterialTheme.typography.titleMedium, color = onError)
                listOf(
                    "Open device Settings",
                    "Go to Accessibility",
                    "Find \"Otterling\"",
                    "Turn the switch on",
                ).forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onError.copy(alpha = 0.9f),
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = onError,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Re-enable in Settings", style = MaterialTheme.typography.titleMedium)
        }
    }
}
