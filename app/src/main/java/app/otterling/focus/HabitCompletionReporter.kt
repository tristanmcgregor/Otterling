package app.otterling.focus

import android.content.Context
import android.util.Log
import app.otterling.alerts.MacTamperPollSettings
import app.otterling.content.CloudFilterSettings
import app.otterling.content.DashboardConfigStore
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
 * Best-effort, matching [app.otterling.alerts.TamperReporter]'s stance: a failed report never
 * affects this phone's own local enforcement, which reads [detectedHabitRows] directly and
 * doesn't depend on this call succeeding.
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
        val idsByName = (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }
            .associate { it.optString("name").lowercase() to it.optString("id") }

        val today = LocalDate.now().toString() // "YYYY-MM-DD", matches the server's expected format
        for (name in doneHabitNames) {
            val habitId = idsByName[name.lowercase()] ?: continue
            if (prefs.getString(keyFor(habitId), null) == today) continue // already reported today
            try {
                report(host, settings.token(), habitId, today)
                prefs.edit().putString(keyFor(habitId), today).apply()
            } catch (error: Exception) {
                Log.w(TAG, "habit completion report failed for $habitId", error)
            }
        }
    }

    private fun report(host: String, token: String, habitId: String, date: String) {
        val body = JSONObject()
            .put("date", date)
            .put("device_id", DashboardConfigStore(context).deviceId())
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
        if (code !in 200..299) {
            Log.w(TAG, "habit completion report for $habitId got HTTP $code")
        } else {
            Log.i(TAG, "reported habit $habitId done for $date")
        }
    }

    private fun keyFor(habitId: String) = "last_reported_date_$habitId"

    private companion object {
        const val TAG = "HabitCompletionReporter"
        const val PREFS = "habit_completion_reporter"
    }
}
