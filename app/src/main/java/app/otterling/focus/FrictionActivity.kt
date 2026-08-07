package app.otterling.focus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.otterling.ui.theme.FamilyGuardTheme
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
            FamilyGuardTheme {
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
    val total = delaySeconds.coerceAtLeast(1)
    val progress by animateFloatAsState(
        targetValue = remaining.toFloat() / total,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "countdown",
    )
    val secondary = MaterialTheme.colorScheme.secondary
    val done = remaining <= 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Pause.",
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Is opening this app the best use of your time right now?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))

        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                drawCircle(
                    color = secondary.copy(alpha = 0.2f),
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = secondary,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            if (done) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Text(
                    "$remaining",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onContinue,
            enabled = done,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        ) {
            Text(if (done) "Continue to app" else "Wait...")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
            Text("Close app", color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
