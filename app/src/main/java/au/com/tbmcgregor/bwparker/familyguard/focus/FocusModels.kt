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
 * A user-defined command: "[targetPackageName] is blocked until (a completion pattern is seen in
 * [triggerPackageName]), then it unlocks for [unlockMinutes] minutes". [targetPackageName] is
 * suspended by default (like a [RewardApp]) and only opens while one of its rules has an active
 * unlock window. [lastGrantedEpochDay] makes firing idempotent per calendar day; [unlockUntilMillis]
 * is this rule's own currently-active unlock expiry (a target with multiple rules unlocks if any of
 * them is currently active).
 *
 * [habitName] holds the raw, possibly multi-habit condition: null/blank means "any/all habits
 * complete" (the original whole-tracker pattern match); otherwise it's one or more habit names
 * from [DetectedHabit], joined with [HABIT_NAME_DELIMITER], ALL of which must be done today for
 * the rule to fire. Use [requiredHabitNames]/[encodeRequiredHabitNames] rather than touching
 * [habitName] directly.
 *
 * [windowStartMinute]/[windowEndMinute] (both minutes-since-local-midnight, 0..1439, or both null)
 * make this rule only enforce blocking during that time-of-day window instead of the "unlock for
 * unlockMinutes once done" model: outside the window, or once the required habit(s) are done today,
 * the target is unblocked; inside the window while they're not done, it's blocked. [windowEndMinute]
 * of 0 means "through to midnight" (wraps around), so e.g. start=1260 (9pm) end=0 covers 9pm-midnight.
 * A windowed rule requires specific [requiredHabitNames] (not the "all habits" pattern), since only
 * per-habit done state is persisted for [reapplyAll] to check outside of a live scan.
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
    val windowStartMinute: Int? = null,
    val windowEndMinute: Int? = null,
)

/** Non-printable separator, so it can't collide with a real habit name typed by the user. */
private const val HABIT_NAME_DELIMITER = "\u001F"

/** Decodes [HabitRule.habitName] into the list of habit names that must ALL be done today for this
 * rule to fire. Empty means "any/all habits complete". */
fun HabitRule.requiredHabitNames(): List<String> =
    habitName?.split(HABIT_NAME_DELIMITER)?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

/** Encodes a list of required habit names for storage in [HabitRule.habitName]. An empty/blank-only
 * list encodes to null, meaning "any/all habits complete". */
fun encodeRequiredHabitNames(names: List<String>): String? =
    names.map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(HABIT_NAME_DELIMITER)

/** True if this rule only enforces blocking during a specific time-of-day window (see
 * [HabitRule.windowStartMinute]/[HabitRule.windowEndMinute]) rather than the "unlock for N minutes
 * once done" model. */
fun HabitRule.isTimeWindowed(): Boolean = windowStartMinute != null && windowEndMinute != null

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
