package app.otterling.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import app.otterling.focus.DetectedHabit
import app.otterling.focus.DetectedHabitManager
import app.otterling.focus.HabitProofManager
import app.otterling.focus.HabitProofRequirement
import app.otterling.focus.ImageMatcher
import app.otterling.focus.ProofSettings
import app.otterling.focus.HabitShareApiClient
import app.otterling.focus.HabitShareSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen HabitShare settings hub reached from the main Settings list. Groups everything
 * habit-related that used to be scattered inline: the account connection, per-habit image
 * verification, and the rule command-builder.
 */
@Composable
fun HabitShareSettingsScreen(context: Context, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("HabitShare", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HabitShareAccountSection(context)
            HabitShareVerificationSection(context)
        }
    }
}

/**
 * Connect / disconnect the user's own HabitShare login. When connected, [HabitShareSyncManager]
 * reads exact done/not-done status directly from HabitShare's server every 30s. This is the only
 * source of habit completion data -- habits aren't read until an account is connected.
 */
@Composable
fun HabitShareAccountSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val apiClient = remember { HabitShareApiClient(context) }
    var connected by remember { mutableStateOf(apiClient.isConnected()) }
    var username by remember { mutableStateOf(apiClient.connectedUsername().orEmpty()) }
    var showLoginForm by remember { mutableStateOf(false) }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

    SectionCard(
        title = "HabitShare Account",
        icon = Icons.Default.CloudSync,
        subtitle = "Connect your HabitShare login so completions are read straight from " +
            "HabitShare's own server. This is the only way habits are detected.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (connected) "Connected" else "Not connected",
                style = MaterialTheme.typography.bodyLarge,
            )
            StatusText(if (connected) "Live sync" else "Not connected", isGood = connected)
        }
        if (connected) {
            Text("Signed in as $username", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                apiClient.disconnect()
                connected = false
                username = ""
            }) { Text("Disconnect") }
        } else {
            if (!showLoginForm) {
                Button(onClick = { showLoginForm = true }) { Text("Connect account") }
            } else {
                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
                    label = { Text("HabitShare username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("HabitShare password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                loginError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isLoggingIn && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
                        onClick = {
                            coroutineScope.launch {
                                isLoggingIn = true
                                loginError = null
                                when (apiClient.login(usernameInput, passwordInput)) {
                                    is HabitShareApiClient.LoginResult.Success -> {
                                        connected = true
                                        username = usernameInput
                                        showLoginForm = false
                                        passwordInput = ""
                                    }
                                    is HabitShareApiClient.LoginResult.InvalidCredentials ->
                                        loginError = "Incorrect username or password."
                                    is HabitShareApiClient.LoginResult.NetworkError ->
                                        loginError = "Couldn't reach HabitShare -- check your connection and try again."
                                }
                                isLoggingIn = false
                            }
                        },
                    ) { Text(if (isLoggingIn) "Connecting..." else "Log in") }
                    OutlinedButton(onClick = { showLoginForm = false; loginError = null }) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * "General settings" the user asked for: one checkbox per detected habit deciding whether ticking
 * it in HabitShare also demands a same-day photo that visually matches a reference photo before it
 * counts. Enforcement of "ticked yes + image verified" lives in [HabitProofManager.filterSatisfied]
 * / [HabitProofManager.namesNeedingProof]; this screen just configures it.
 */
@Composable
fun HabitShareVerificationSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val detectedHabitManager = remember { DetectedHabitManager(context) }
    val habitProofManager = remember { HabitProofManager(context) }
    val proofSettings = remember { ProofSettings(context) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var detectedHabits by remember { mutableStateOf<List<DetectedHabit>>(emptyList()) }
    var proofRequirements by remember { mutableStateOf<List<HabitProofRequirement>>(emptyList()) }
    var sensitivity by remember { mutableStateOf(proofSettings.sensitivity()) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        isRefreshing = true
        withContext(Dispatchers.IO) { runCatching { HabitShareSyncManager(context).syncIfConnected() } }
        detectedHabits = detectedHabitManager.latest()
        proofRequirements = habitProofManager.requirements()
        isRefreshing = false
    }

    SectionCard(
        title = "Image Verification",
        icon = Icons.Default.PhotoCamera,
        subtitle = "Tick a habit to require photo proof. When required, ticking it in HabitShare " +
            "isn't enough on its own -- it only counts toward unblocking an app once you've also " +
            "taken a same-day photo that visually matches a reference photo you set here.",
    ) {
        Text("Match strictness", style = MaterialTheme.typography.bodyLarge)
        Text(
            "How closely a daily photo must match a reference. Stricter rejects more (harder to " +
                "cheat); more lenient accepts more (fewer genuine photos refused).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ImageMatcher.Sensitivity.entries.forEach { option ->
                val label = when (option) {
                    ImageMatcher.Sensitivity.LENIENT -> "Lenient"
                    ImageMatcher.Sensitivity.NORMAL -> "Normal"
                    ImageMatcher.Sensitivity.STRICT -> "Strict"
                }
                if (option == sensitivity) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(label, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = {
                            sensitivity = option
                            proofSettings.setSensitivity(option)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
        HorizontalDivider()

        if (detectedHabits.isEmpty()) {
            Text(
                "No habits detected yet. Open HabitShare (or connect your account above) so your " +
                    "habits show up here, then refresh.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                detectedHabits.forEachIndexed { index, habit ->
                    if (index > 0) HorizontalDivider()
                    Text(habit.name, style = MaterialTheme.typography.bodyMedium)
                    ProofRequirementRow(
                        habitName = habit.name,
                        requirement = proofRequirements.find { it.habitName.equals(habit.name, ignoreCase = true) },
                        onSetRequirement = { required, referencePhotoPath ->
                            coroutineScope.launch {
                                habitProofManager.setRequirement(habit.name, required, referencePhotoPath)
                                proofRequirements = habitProofManager.requirements()
                            }
                        },
                    )
                }
            }
        }
        OutlinedButton(onClick = { refreshTrigger++ }, enabled = !isRefreshing) {
            Text(if (isRefreshing) "Refreshing…" else "Refresh")
        }
    }
}

/**
 * "Requires image proof" checkbox for one habit: turning it on immediately opens the camera to
 * take a reference photo (required before it can be turned on at all -- there's nothing to compare
 * against otherwise); turning it off clears the requirement and all reference photos. You can add
 * several reference photos (e.g. different angles/lighting) -- a daily proof only has to match any
 * one of them, which makes genuine matches far more forgiving without loosening the threshold.
 * Shown both in the rule-builder condition picker and in the standalone detected-habits tuning
 * list, sharing the same underlying [HabitProofRequirement] since multiple rules can gate on the
 * same habit name.
 */
@Composable
private fun ProofRequirementRow(
    habitName: String,
    requirement: HabitProofRequirement?,
    onSetRequirement: (required: Boolean, referencePhotoPath: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refVersion by remember(habitName) { mutableIntStateOf(0) }
    val references = remember(habitName, refVersion, requirement?.referencePhotoPath) {
        HabitProofManager.referenceFiles(context, habitName, requirement?.referencePhotoPath)
    }
    // A row flagged required but with no readable reference photo (e.g. a relic of an older build)
    // can never be satisfied -- show it unchecked so re-checking walks through capturing one again.
    val required = requirement?.required == true && references.isNotEmpty()

    var pendingCapture by remember { mutableStateOf<File?>(null) }
    val takeReferencePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val target = pendingCapture
        pendingCapture = null
        if (success && target != null) {
            refVersion++
            onSetRequirement(true, target.absolutePath)
        }
    }

    fun captureReference() {
        val target = HabitProofManager.newReferenceFile(context, habitName)
        pendingCapture = target
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        takeReferencePhoto.launch(uri)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                if (required) {
                    HabitProofManager.clearReferences(context, habitName)
                    refVersion++
                    onSetRequirement(false, null)
                } else {
                    captureReference()
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = required,
                onCheckedChange = { checked ->
                    if (checked) {
                        captureReference()
                    } else {
                        HabitProofManager.clearReferences(context, habitName)
                        refVersion++
                        onSetRequirement(false, null)
                    }
                },
            )
            Text("Requires image proof (must match a reference photo)", style = MaterialTheme.typography.bodySmall)
        }
        if (required) {
            Column(modifier = Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                references.forEach { ref ->
                    // Decoding is real file + JPEG-decode I/O -- doing it inline inside `remember{}`
                    // ran it synchronously on the main/composition thread for every reference photo
                    // row, every time this recomposed. Loading it asynchronously via LaunchedEffect
                    // keeps composition itself non-blocking; the row just shows no image for one
                    // frame while it decodes.
                    var bitmap by remember(ref.absolutePath, ref.lastModified()) { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(ref.absolutePath, ref.lastModified()) {
                        bitmap = withContext(Dispatchers.IO) {
                            runCatching {
                                BitmapFactory.Options().apply { inSampleSize = 4 }
                                    .let { opts -> BitmapFactory.decodeFile(ref.absolutePath, opts) }
                            }.getOrNull()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        bitmap?.let { loadedBitmap ->
                            Image(
                                bitmap = loadedBitmap.asImageBitmap(),
                                contentDescription = "Reference photo for $habitName",
                                modifier = Modifier.height(56.dp),
                            )
                        }
                        TextButton(onClick = {
                            HabitProofManager.deleteReference(ref)
                            refVersion++
                            val remaining = HabitProofManager.referenceFiles(context, habitName)
                            if (remaining.isEmpty()) onSetRequirement(false, null)
                            else onSetRequirement(true, remaining.first().absolutePath)
                        }) { Text("Remove") }
                    }
                }
                TextButton(onClick = { captureReference() }) {
                    Text("Add another reference photo")
                }
            }
        }
    }
}
