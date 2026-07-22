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

/** Singleton row (id is always 0) tracking unspent reward minutes earned from focus sessions/habits. */
@Entity(tableName = "reward_ledger")
data class RewardLedger(
    @PrimaryKey val id: Int = 0,
    val earnedMinutesRemaining: Int = 0,
)

/**
 * A user-defined command: "when (a completion pattern is seen in [triggerPackageName]) -> unlock
 * [targetPackageName] for [unlockMinutes] minutes". [targetPackageName] is suspended by default
 * (like a [RewardApp]) and only opens while one of its rules has an active unlock window.
 * [lastGrantedEpochDay] makes firing idempotent per calendar day; [unlockUntilMillis] is this
 * rule's own currently-active unlock expiry (a target with multiple rules unlocks if any of them
 * is currently active). [habitName] is null for "any/all habits complete" (the original
 * whole-tracker pattern match), or a specific habit name from [DetectedHabit] to gate on just
 * that one habit instead.
 */
@Entity(tableName = "habit_rules")
data class HabitRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPackageName: String,
    val targetPackageName: String,
    val unlockMinutes: Int,
    val enabled: Boolean = true,
    val lastGrantedEpochDay: Long = -1,
    val unlockUntilMillis: Long = 0,
    val habitName: String? = null,
)

/**
 * A single habit row detected by [HabitTrackerScanner] scanning the tracker app's accessibility
 * tree -- pairing each checkbox/toggle-like node with its nearest text label. This is a live
 * snapshot refreshed on every scan (rows that scroll off screen just go stale, not deleted), not a
 * historical log -- see [DetectedHabit] usage in Settings for the debug listing that helps tune
 * detection, and in [HabitRule.habitName] for gating rewards on one specific habit.
 */
@Entity(tableName = "detected_habits")
data class DetectedHabit(
    @PrimaryKey val name: String,
    val doneToday: Boolean,
    val dateEpochDay: Long,
)
