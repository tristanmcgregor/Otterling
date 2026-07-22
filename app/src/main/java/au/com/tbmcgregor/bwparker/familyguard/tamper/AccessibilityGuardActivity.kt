package au.com.tbmcgregor.bwparker.familyguard.tamper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.com.tbmcgregor.bwparker.familyguard.restrictions.AccessibilityGuard
import kotlinx.coroutines.delay

/**
 * Full-screen, un-dismissable nag shown whenever [AccessibilityGuard.isEnabled] is found false
 * (see `ProtectionEnforcementService`/`RestrictionEnforcementWorker`). Swallows back presses and
 * pins itself via Lock Task Mode -- as device owner this enters pinning directly, no user
 * confirmation dialog -- so Home/Recents can't be used to escape it either. Polls every 1.5s and
 * releases itself (unpins, finishes) the moment the accessibility service is back on; there's no
 * other way to dismiss it, by design, mirroring [au.com.tbmcgregor.bwparker.familyguard.focus.FrictionActivity].
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
            MaterialTheme {
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
        isShowing = false
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
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.height(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Accessibility access was turned off",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Family Device Guard needs this permission to enforce friction screens, time " +
                "budgets, and habit-based unlocks. Turn it back on to keep using this device.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onOpenSettings) { Text("Open Accessibility settings") }
    }
}
