package au.com.tbmcgregor.bwparker.familyguard.focus

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An app that's suspended by default and only opens while reward minutes are being spent. */
@Entity(tableName = "reward_apps")
data class RewardApp(
    @PrimaryKey val packageName: String,
)

/** An app that shows a short friction/delay screen before opening, instead of a hard block. */
@Entity(tableName = "mindful_apps")
data class MindfulApp(
    @PrimaryKey val packageName: String,
    val delaySeconds: Int = 20,
)

/**
 * A daily foreground-time cap for an app, with an optional stricter sub-limit for a
 * feature-within-the-app detected heuristically by [FocusGuardAccessibilityService] (e.g. YouTube
 * Shorts) -- `subLimitLabel` is just a display name for that sub-feature.
 */
@Entity(tableName = "app_time_budgets")
data class AppTimeBudget(
    @PrimaryKey val packageName: String,
    val dailyLimitMinutes: Int,
    val subLimitMinutes: Int? = null,
    val subLimitLabel: String? = null,
)

/** Running daily foreground-time counters backing [AppTimeBudget] enforcement. */
@Entity(tableName = "app_usage_counters", primaryKeys = ["packageName", "dateEpochDay"])
data class AppUsageCounter(
    val packageName: String,
    val dateEpochDay: Long,
    val totalSeconds: Int = 0,
    val subSeconds: Int = 0,
)

/** A single voluntary focus session; completing one (not cancelling early) earns reward minutes. */
@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val plannedMinutes: Int,
    val endedAtMillis: Long? = null,
    val completed: Boolean = false,
)

/** Whether today's habit-tracker completion has already been detected and rewarded (once/day). */
@Entity(tableName = "habit_gate_state")
data class HabitGateState(
    @PrimaryKey val dateEpochDay: Long,
    val rewardGranted: Boolean = false,
)

/** Singleton row (id is always 0) tracking unspent reward minutes earned from focus sessions/habits. */
@Entity(tableName = "reward_ledger")
data class RewardLedger(
    @PrimaryKey val id: Int = 0,
    val earnedMinutesRemaining: Int = 0,
)
