package au.com.tbmcgregor.bwparker.familyguard.focus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen "prove it" prompt shown by [FocusGuardAccessibilityService] when a habit configured
 * in [HabitProofManager] as requiring proof gets ticked in HabitShare without one yet today: take
 * a photo plus a short note (e.g. which chapter was read) before that tick is allowed to satisfy
 * any [HabitRule]. Dismissing without submitting just leaves the habit un-trusted -- it'll be
 * re-prompted on the next scan since the underlying tick is still there with no proof logged.
 */
class HabitProofActivity : ComponentActivity() {
    private var photoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME)
        if (habitName == null) {
            finish()
            return
        }

        val dir = File(filesDir, "habit_proofs").apply { mkdirs() }
        val safeName = habitName.replace(Regex("[^A-Za-z0-9]"), "_").take(60)
        val file = File(dir, "${safeName}_${System.currentTimeMillis()}.jpg")
        photoFile = file
        val photoUri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

        setContent {
            var photoTaken by remember { mutableStateOf(false) }
            var preview by remember { mutableStateOf<Bitmap?>(null) }
            var note by remember { mutableStateOf("") }

            val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                photoTaken = success
                if (success) {
                    lifecycleScope.launch {
                        preview = withContext(Dispatchers.IO) { decodeShrunk(file) }
                    }
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HabitProofScreen(
                        habitName = habitName,
                        photoTaken = photoTaken,
                        preview = preview,
                        note = note,
                        onNoteChange = { note = it },
                        onTakePhoto = { takePicture.launch(photoUri) },
                        onSubmit = {
                            lifecycleScope.launch {
                                val manager = HabitProofManager(applicationContext)
                                manager.recordProof(habitName, file.absolutePath, note.trim())
                                withContext(Dispatchers.Default) {
                                    HabitRuleManager(applicationContext).reapplyAll()
                                }
                                Toast.makeText(applicationContext, "Proof saved for $habitName", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        },
                        onSkip = { finish() },
                    )
                }
            }
        }
    }

    private fun decodeShrunk(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    override fun onDestroy() {
        isShowing = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HABIT_NAME = "extra_habit_name"

        @Volatile private var isShowing = false

        /** Safe to call repeatedly (e.g. from a scan loop) -- no-ops while already showing so a
         * fast-firing scan can't stack duplicate prompts. */
        fun launch(context: android.content.Context, habitName: String) {
            if (isShowing) return
            val intent = android.content.Intent(context, HabitProofActivity::class.java)
                .putExtra(EXTRA_HABIT_NAME, habitName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

@Composable
private fun HabitProofScreen(
    habitName: String,
    photoTaken: Boolean,
    preview: Bitmap?,
    note: String,
    onNoteChange: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onSubmit: () -> Unit,
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
            "Take a photo showing you actually did this, and note what you did. This has to be " +
                "submitted before this habit counts towards unlocking anything today.",
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

        Button(onClick = onTakePhoto) {
            Text(if (photoTaken) "Retake photo" else "Take photo")
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("What did you do? (e.g. chapter read)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(24.dp))

        Button(onClick = onSubmit, enabled = photoTaken && note.isNotBlank()) {
            Text("Submit proof")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSkip) {
            Text("Not now")
        }
    }
}
