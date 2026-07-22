package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ProofMatchState {
    object Idle : ProofMatchState
    object Checking : ProofMatchState
    object Matched : ProofMatchState
    object NoMatch : ProofMatchState
}

/**
 * Full-screen "prove it" prompt shown by [FocusGuardAccessibilityService] when a habit configured
 * in [HabitProofManager] as requiring proof gets ticked in HabitShare without approved proof yet
 * today: take a photo, which is automatically compared against the habit's stored reference photo
 * via [ImageMatcher] -- only a visual match is recorded and allowed to satisfy any [HabitRule]. A
 * non-match shows an inline "doesn't match" message and lets you retake; dismissing without a
 * match just leaves the habit un-trusted, re-prompted on the next scan.
 *
 * `singleTask` launch mode (see manifest) means a repeat [launch] call while an instance already
 * exists -- even backgrounded via Home, not just currently visible -- brings that same instance
 * back to front via [onNewIntent] instead of silently no-op'ing or stacking a duplicate. That
 * matters here: earlier this relied on a static "isShowing" flag reset only in onDestroy, which
 * got permanently stuck true (blocking every future prompt) the moment a user backgrounded the
 * activity without resolving it, since backgrounding alone doesn't destroy an activity.
 */
class HabitProofActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME)
        if (habitName == null) {
            finish()
            return
        }

        val dir = File(filesDir, "habit_proofs").apply { mkdirs() }
        val safeName = habitName.replace(Regex("[^A-Za-z0-9]"), "_").take(60)
        val file = File(dir, "${safeName}_${System.currentTimeMillis()}.jpg")
        val photoUri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val manager = HabitProofManager(applicationContext)

        setContent {
            var matchState by remember { mutableStateOf<ProofMatchState>(ProofMatchState.Idle) }
            var preview by remember { mutableStateOf<Bitmap?>(null) }
            var referenceMissing by remember { mutableStateOf(false) }

            val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (!success) return@rememberLauncherForActivityResult
                matchState = ProofMatchState.Checking
                lifecycleScope.launch {
                    preview = withContext(Dispatchers.IO) { decodeShrunk(file) }
                    val referencePath = manager.requirement(habitName)?.referencePhotoPath
                    if (referencePath == null) {
                        referenceMissing = true
                        return@launch
                    }
                    val matches = withContext(Dispatchers.Default) {
                        ImageMatcher.isMatch(file, File(referencePath))
                    }
                    if (matches) {
                        manager.recordProof(habitName, file.absolutePath)
                        withContext(Dispatchers.Default) { HabitRuleManager(applicationContext).reapplyAll() }
                        matchState = ProofMatchState.Matched
                        Toast.makeText(applicationContext, "Approved: $habitName", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        matchState = ProofMatchState.NoMatch
                    }
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (referenceMissing) {
                        NoReferencePhotoScreen(habitName = habitName, onDismiss = { finish() })
                    } else {
                        HabitProofScreen(
                            habitName = habitName,
                            matchState = matchState,
                            preview = preview,
                            onTakePhoto = { takePicture.launch(photoUri) },
                            onSkip = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun decodeShrunk(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    /** With `singleTask`, a repeat [launch] reuses this instance and delivers here instead of
     * onCreate -- rebuild it against the (possibly different) habit name in the new intent. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    companion object {
        const val EXTRA_HABIT_NAME = "extra_habit_name"

        /** Safe to call repeatedly (e.g. from a scan loop) -- `singleTask` + these flags mean this
         * either creates the prompt fresh, or brings an already-existing instance (foreground or
         * backgrounded) back to front rather than stacking a duplicate or silently no-op'ing. */
        fun launch(context: android.content.Context, habitName: String) {
            val intent = Intent(context, HabitProofActivity::class.java)
                .putExtra(EXTRA_HABIT_NAME, habitName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }
    }
}

@Composable
private fun HabitProofScreen(
    habitName: String,
    matchState: ProofMatchState,
    preview: Bitmap?,
    onTakePhoto: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Prove it: $habitName", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Take a photo showing you actually doing this. It's automatically checked against " +
                "your reference photo -- only a match unlocks anything gated on this habit today.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Proof photo preview",
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
            Spacer(Modifier.height(16.dp))
        }

        when (matchState) {
            ProofMatchState.Checking -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Checking against your reference photo...", style = MaterialTheme.typography.bodySmall)
            }
            ProofMatchState.NoMatch -> {
                Text(
                    "Doesn't match your reference photo -- try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
            else -> {}
        }

        Button(onClick = onTakePhoto, enabled = matchState != ProofMatchState.Checking) {
            Text(if (matchState == ProofMatchState.NoMatch) "Retake photo" else "Take photo")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip) {
            Text("Not now")
        }
    }
}

@Composable
private fun NoReferencePhotoScreen(habitName: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No reference photo set for \"$habitName\"", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Go to Habit Rules in Settings and re-enable photo proof for this habit to set one.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss) { Text("OK") }
    }
}
