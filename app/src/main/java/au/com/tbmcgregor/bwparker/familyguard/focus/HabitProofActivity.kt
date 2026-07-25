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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import au.com.tbmcgregor.bwparker.familyguard.ui.theme.FamilyGuardTheme
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
 * "Prove it" prompt shown by [FocusGuardAccessibilityService] when a habit configured in
 * [HabitProofManager] as requiring proof gets ticked in HabitShare without approved proof yet
 * today. It launches the device's native camera directly on open (no intermediate in-app
 * "camera" screen) -- matching how reference-photo capture works in Settings -- then compares the
 * captured photo against the habit's stored reference photo via [ImageMatcher]; only a visual
 * match is recorded and allowed to satisfy any [HabitRule]. A non-match shows an inline "doesn't
 * match" message and lets you retake; cancelling the camera drops back to a small themed prompt so
 * the user is never stuck on a blank screen.
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
        // Load the embedding model in the background so the first comparison isn't slowed by it.
        lifecycleScope.launch(Dispatchers.Default) { ImageMatcher.warmUp(applicationContext) }

        setContent {
            var matchState by remember { mutableStateOf<ProofMatchState>(ProofMatchState.Idle) }
            var preview by remember { mutableStateOf<Bitmap?>(null) }
            var referenceMissing by remember { mutableStateOf(false) }
            // Whether the user backed out of the native camera without taking a photo -- controls
            // showing the themed "take photo / not now" fallback instead of a blank waiting screen.
            var cameraCancelled by remember { mutableStateOf(false) }
            // Survives recreate()/onNewIntent + recomposition so the camera auto-launches exactly
            // once per prompt rather than relaunching in a loop.
            var hasLaunched by rememberSaveable { mutableStateOf(false) }

            val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (!success) {
                    cameraCancelled = true
                    return@rememberLauncherForActivityResult
                }
                cameraCancelled = false
                matchState = ProofMatchState.Checking
                lifecycleScope.launch {
                    preview = withContext(Dispatchers.IO) { decodeShrunk(file) }
                    val requirement = manager.requirement(habitName)
                    val references = withContext(Dispatchers.IO) {
                        HabitProofManager.referenceFiles(applicationContext, habitName, requirement?.referencePhotoPath)
                    }
                    if (references.isEmpty()) {
                        referenceMissing = true
                        return@launch
                    }
                    val sensitivity = ProofSettings(applicationContext).sensitivity()
                    val matches = withContext(Dispatchers.Default) {
                        ImageMatcher.isMatch(applicationContext, file, references, sensitivity)
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

            val launchCamera: () -> Unit = {
                cameraCancelled = false
                takePicture.launch(photoUri)
            }

            // Auto-open the native camera the moment the prompt appears, so there's no confusing
            // intermediate in-app "camera" screen.
            LaunchedEffect(Unit) {
                if (!hasLaunched) {
                    hasLaunched = true
                    launchCamera()
                }
            }

            FamilyGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (referenceMissing) {
                        NoReferencePhotoScreen(habitName = habitName, onDismiss = { finish() })
                    } else {
                        HabitProofScreen(
                            habitName = habitName,
                            matchState = matchState,
                            preview = preview,
                            cameraCancelled = cameraCancelled,
                            onTakePhoto = launchCamera,
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
    cameraCancelled: Boolean,
    onTakePhoto: () -> Unit,
    onSkip: () -> Unit,
) {
    val teal = Color(0xFF14B8A6)
    val red = Color(0xFFEF4444)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (preview != null && matchState != ProofMatchState.Matched) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Proof photo preview",
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Spacer(Modifier.height(20.dp))
        }

        when (matchState) {
            ProofMatchState.Checking -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Checking against your reference...",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            ProofMatchState.Matched -> {
                ResultBadge(teal, matched = true)
                Spacer(Modifier.height(16.dp))
                Text("Verified!", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your apps are now unlocked.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ProofMatchState.NoMatch -> {
                ResultBadge(red, matched = false)
                Spacer(Modifier.height(16.dp))
                Text("Not quite right", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "That doesn't look close enough to your reference photo. Frame it more like " +
                        "the reference and retake. (Add more reference angles or lower the match " +
                        "strictness under HabitShare settings.)",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                    Text("Retake photo")
                }
            }
            ProofMatchState.Idle -> {
                if (cameraCancelled) {
                    Text(
                        "Prove it: $habitName",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Take a photo showing you doing this. It's checked against your reference " +
                            "photo -- only a match unlocks anything gated on this habit today.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Take photo")
                    }
                    TextButton(onClick = onSkip) { Text("Not now") }
                } else {
                    // Native camera is opening (or reopening) -- brief themed waiting state.
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Opening camera...",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultBadge(color: Color, matched: Boolean) {
    Box(
        modifier = Modifier.size(80.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (matched) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
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
