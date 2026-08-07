package app.otterling.focus

import android.content.Context
import app.otterling.data.AppDatabase

/**
 * A voluntary Pomodoro-style timer: reward apps stay suspended for the duration, and completing
 * the full planned time (not cancelling early) earns reward minutes 1:1 via [RewardLedgerManager].
 */
class FocusSessionManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).focusSessionDao()
    private val ledger = RewardLedgerManager(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class ActiveSession(val id: Long, val startedAtMillis: Long, val plannedMinutes: Int) {
        fun endsAtMillis(): Long = startedAtMillis + plannedMinutes * 60_000L
    }

    fun activeSession(): ActiveSession? {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id < 0) return null
        return ActiveSession(
            id = id,
            startedAtMillis = prefs.getLong(KEY_STARTED, 0L),
            plannedMinutes = prefs.getInt(KEY_MINUTES, 0),
        )
    }

    suspend fun start(plannedMinutes: Int): ActiveSession {
        cancelActive()
        val now = System.currentTimeMillis()
        val id = dao.insert(FocusSession(startedAtMillis = now, plannedMinutes = plannedMinutes))
        prefs.edit()
            .putLong(KEY_ID, id)
            .putLong(KEY_STARTED, now)
            .putInt(KEY_MINUTES, plannedMinutes)
            .apply()
        return ActiveSession(id, now, plannedMinutes)
    }

    /** Call once the planned duration has actually elapsed. Returns minutes earned (0 if none active). */
    suspend fun complete(): Int {
        val active = activeSession() ?: return 0
        dao.finish(active.id, System.currentTimeMillis(), completed = true)
        clearActive()
        ledger.addMinutes(active.plannedMinutes)
        return active.plannedMinutes
    }

    /** Ends the session early -- no reward. */
    suspend fun cancelActive() {
        val active = activeSession() ?: return
        dao.finish(active.id, System.currentTimeMillis(), completed = false)
        clearActive()
    }

    suspend fun recentSessions(limit: Int = 20) = dao.recent(limit)

    private fun clearActive() {
        prefs.edit().remove(KEY_ID).remove(KEY_STARTED).remove(KEY_MINUTES).apply()
    }

    private companion object {
        const val PREFS_NAME = "focus_session_state"
        const val KEY_ID = "active_id"
        const val KEY_STARTED = "active_started"
        const val KEY_MINUTES = "active_minutes"
    }
}
