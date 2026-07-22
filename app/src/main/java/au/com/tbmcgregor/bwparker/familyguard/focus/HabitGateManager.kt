package au.com.tbmcgregor.bwparker.familyguard.focus

import android.content.Context
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import java.time.LocalDate

/**
 * Rewards completing today's habits in a separate habit tracker app (e.g. HabitShare), detected
 * by [FocusGuardAccessibilityService] scanning that app's on-screen text for a completion pattern.
 * There's no public HabitShare API, so this is inherently a heuristic against its UI -- see
 * [lastCapturedText] for a way to see what the detector is actually reading, to help tune it.
 */
class HabitGateManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).habitGateStateDao()
    private val ledger = RewardLedgerManager(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var trackerPackageName: String?
        get() = prefs.getString(KEY_PACKAGE, null)
        set(value) = prefs.edit().putString(KEY_PACKAGE, value).apply()

    var rewardMinutes: Int
        get() = prefs.getInt(KEY_REWARD_MINUTES, DEFAULT_REWARD_MINUTES)
        set(value) = prefs.edit().putInt(KEY_REWARD_MINUTES, value).apply()

    /** Last screen text captured from the tracker app -- use this to tune the detection pattern. */
    var lastCapturedText: String
        get() = prefs.getString(KEY_LAST_CAPTURE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CAPTURE, value).apply()

    suspend fun isGrantedToday(): Boolean = dao.get(todayEpochDay())?.rewardGranted ?: false

    /** Idempotent per day -- safe to call every time the detector thinks it sees "all done". */
    suspend fun grantIfNotAlready(): Boolean {
        if (isGrantedToday()) return false
        dao.upsert(HabitGateState(todayEpochDay(), rewardGranted = true))
        ledger.addMinutes(rewardMinutes)
        return true
    }

    private companion object {
        const val PREFS_NAME = "habit_gate_prefs"
        const val KEY_PACKAGE = "tracker_package"
        const val KEY_REWARD_MINUTES = "reward_minutes"
        const val KEY_LAST_CAPTURE = "last_capture"
        const val DEFAULT_REWARD_MINUTES = 20
        fun todayEpochDay(): Long = LocalDate.now().toEpochDay()
    }
}
