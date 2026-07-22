package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Full-screen delay/friction prompt shown by [FocusGuardAccessibilityService] right as a
 * "mindful" app comes to the foreground -- a speed bump for apps you sometimes need but tend to
 * open on autopilot, rather than a hard block.
 */
class FrictionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 20)
        if (packageName == null) {
            finish()
            return
        }
        val mindfulAppManager = MindfulAppManager(applicationContext)

        // Swallow back presses -- dodging via back would defeat the point of the delay.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FrictionScreen(
                        delaySeconds = delaySeconds,
                        onContinue = {
                            mindfulAppManager.markPassed(packageName)
                            finish()
                        },
                        onGoHome = {
                            startActivity(
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_DELAY_SECONDS = "extra_delay_seconds"
    }
}

@Composable
private fun FrictionScreen(delaySeconds: Int, onContinue: () -> Unit, onGoHome: () -> Unit) {
    var remaining by remember { mutableIntStateOf(delaySeconds) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Take a moment", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Do you still want to open this app?",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (remaining > 0) {
            Text("$remaining", style = MaterialTheme.typography.displayLarge)
        } else {
            Button(onClick = onContinue) { Text("Continue anyway") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onGoHome) { Text("Never mind, go home") }
    }
}
