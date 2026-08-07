package app.otterling.focus

import android.content.Context
import app.otterling.data.AppDatabase
import java.time.LocalDate

/**
 * Caches the most recent [HabitTrackerScanner] results so Settings can show what's actually been
 * detected (for tuning) and [HabitRuleManager] can gate rules on one specific habit rather than
 * only "all habits done".
 */
class DetectedHabitManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).detectedHabitDao()

    suspend fun latest(): List<DetectedHabit> = dao.getAll()

    /** Call after every scan of the tracker app -- overwrites each named row's cached state. */
    suspend fun recordScan(rows: List<Pair<String, Boolean>>) {
        if (rows.isEmpty()) return
        val today = LocalDate.now().toEpochDay()
        rows.forEach { (name, done) ->
            dao.upsert(DetectedHabit(name = name.take(120), doneToday = done, dateEpochDay = today))
        }
        dao.deleteNotIn(rows.map { it.first.take(120) })
    }
}
