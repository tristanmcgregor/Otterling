package app.otterling.focus

import android.content.Context
import android.util.Base64
import android.util.Log
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.content.CloudFilterSettings
import app.otterling.content.DashboardConfigStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reports "this habit is done today" to the server's global habit-completion state (see
 * `lockprofile_service.py`'s `HABIT_COMPLETIONS_PATH` / `POST /dashboard-api/habits/<id>/complete`)
 * -- the piece that lets a rule stored under a DIFFERENT device (e.g. a Mac rule blocking Steam
 * until today's reading habit is done) see completion state this phone verified via HabitShare.
 * Called from [HabitRuleManager.evaluateTrigger] for every habit HabitShare reports done today,
 * regardless of whether any of THIS phone's own local rules reference it -- a dashboard rule
 * gating a different device has no way to be visible here, so reporting can't be conditioned on
 * local rule relevance.
 *
 * Matches by name (case-insensitive, exact) against the global habit library
 * ([DashboardConfigStore.globalHabitsSnapshot]) to find which habit id to report against; a
 * HabitShare habit the guardian hasn't also added to the dashboard's habit library has nothing to
 * report against and is silently skipped -- not an error, just not dashboard-managed (yet).
 *
 * If the guardian has flagged a habit `requiresProof` (visible in the same global snapshot), the
 * server rejects a completion report with no photo attached (see lockprofile_service.py's
 * HABIT_PROOFS_DIR comment -- this closes off "just POST with the shared token, no evidence at
 * all" as a way to fake a habit-gated app unlocked anywhere in the fleet). [HabitRuleManager]
 * already only calls this with names [HabitProofManager.filterSatisfied] approved, which for a
 * proof-required habit means today's matched [HabitProofManager.todaysProofPhotoPath] is
 * guaranteed to exist -- that's the exact photo attached here, so the server is shown the same
 * evidence [HabitRuleManager] already trusted locally, not a second unrelated capture.
 *
 * Best-effort, matching [app.otterling.alerts.TamperReporter]'s stance: a failed report never
 * affects this phone's own local enforcement, which reads [detectedHabitRows] directly and
 * doesn't depend on this call succeeding. A failed report also does NOT mark that habit as
 * reported for today, so the next poll cycle retries it.
 */
class HabitCompletionReporter(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun reportDoneToday(doneHabitNames: Set<String>) = withContext(Dispatchers.IO) {
        if (doneHabitNames.isEmpty()) return@withContext
        val settings = MacTamperPollSettings(context)
        if (!settings.isConfigured()) return@withContext
        val host = CloudFilterSettings(context).host()
        if (host.isBlank()) return@withContext

        val habits = DashboardConfigStore(context).globalHabitsSnapshot()?.optJSONArray("habits") ?: return@withContext
        val byName = (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }
            .associateBy { it.optString("name").lowercase() }

        val proofManager = HabitProofManager(context)
        val today = LocalDate.now().toString() // "YYYY-MM-DD", matches the server's expected format
        for (name in doneHabitNames) {
            val habit = byName[name.lowercase()] ?: continue
            val habitId = habit.optString("id")
            if (habitId.isBlank()) continue
            if (prefs.getString(keyFor(habitId), null) == today) continue // already reported today

            var photoBase64: String? = null
            if (habit.optBoolean("requiresProof", false)) {
                val photoPath = proofManager.todaysProofPhotoPath(name)
                val photoFile = photoPath?.let(::File)
                if (photoFile == null || !photoFile.exists()) {
                    // filterSatisfied() should have already excluded this name if proof were
                    // missing -- if we somehow get here anyway, skip rather than send a request
                    // the server will just 400 on with nothing to show for it.
                    Log.w(TAG, "habit $habitId requires proof but no local photo found, skipping report")
                    continue
                }
                photoBase64 = Base64.encodeToString(photoFile.readBytes(), Base64.NO_WRAP)
            }

            val reported = try {
                report(host, settings.token(), habitId, today, photoBase64)
            } catch (error: Exception) {
                Log.w(TAG, "habit completion report failed for $habitId", error)
                false
            }
            if (reported) prefs.edit().putString(keyFor(habitId), today).apply()
        }
    }

    /** Returns true only on a 2xx response -- a caller relies on this to decide whether to mark
     *  the habit as reported for today, so a rejected/failed request must NOT look like success. */
    private fun report(host: String, token: String, habitId: String, date: String, photoBase64: String?): Boolean {
        val body = JSONObject()
            .put("date", date)
            .put("device_id", DashboardConfigStore(context).deviceId())
            .apply { if (photoBase64 != null) put("photo", photoBase64) }
            .toString()
        val connection = URL("https://$host/dashboard-api/habits/$habitId/complete")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        return if (code !in 200..299) {
            Log.w(TAG, "habit completion report for $habitId got HTTP $code")
            false
        } else {
            Log.i(TAG, "reported habit $habitId done for $date")
            true
        }
    }

    private fun keyFor(habitId: String) = "last_reported_date_$habitId"

    private companion object {
        const val TAG = "HabitCompletionReporter"
        const val PREFS = "habit_completion_reporter"
    }
}
