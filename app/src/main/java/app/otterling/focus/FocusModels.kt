package app.otterling.focus

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek

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
 * A user-defined command: "[targetPackages] is blocked until (a completion pattern is seen in
 * [triggerPackageName]), then it unlocks for [unlockMinutes] minutes". Every target app is
 * suspended by default (like a [RewardApp]) and only opens while one of its rules has an active
 * unlock window; all apps on a rule block and unlock together. [lastGrantedEpochDay] makes firing
 * idempotent per calendar day; [unlockUntilMillis] is this rule's own currently-active unlock
 * expiry (an app with multiple rules unlocks if any of them is currently active).
 *
 * [targetPackageName] holds the first target app for backward compatibility; [targetPackages] holds
 * every target app joined with [TARGET_PACKAGE_DELIMITER] (null on rules created before multi-app
 * support, which fall back to the single [targetPackageName]). Use [targetPackageNames]/
 * [encodeTargetPackages] rather than touching either field directly.
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
 * A windowed rule with non-empty [requiredHabitNames] requires those specific habits (not the "all
 * habits" pattern), since only per-habit done state is persisted for [reapplyAll] to check outside
 * of a live scan. A windowed rule with *empty* [requiredHabitNames] has no habit condition at all --
 * it's simply blocked, unconditionally, for the entire window every day (e.g. "no phone before
 * 9am"), and unblocked the rest of the time.
 *
 * [daysOfWeekMask] restricts a *windowed* rule to only apply on certain days (a bitmask over
 * [DayOfWeek], see [daysOfWeekSet]/[encodeDaysOfWeek]) -- on any day not in the mask, the rule
 * behaves as if it were outside its time window (i.e. unblocked) all day. Defaults to every day.
 * Ignored for non-windowed rules.
 */
@Entity(tableName = "habit_rules")
data class HabitRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPackageName: String,
    val targetPackageName: String,
    val targetPackages: String? = null,
    val unlockMinutes: Int,
    val enabled: Boolean = true,
    val lastGrantedEpochDay: Long = -1,
    val unlockUntilMillis: Long = 0,
    val habitName: String? = null,
    val windowStartMinute: Int? = null,
    val windowEndMinute: Int? = null,
    val daysOfWeekMask: Int = ALL_DAYS_OF_WEEK_MASK,
)

/** Bitmask with every [DayOfWeek] set -- the default, meaning "every day". */
const val ALL_DAYS_OF_WEEK_MASK: Int = 0b1111111

/** Decodes a [HabitRule.daysOfWeekMask] value into the set of days it represents. */
fun decodeDaysOfWeek(mask: Int): Set<DayOfWeek> =
    DayOfWeek.entries.filterTo(mutableSetOf()) { mask and (1 shl (it.value - 1)) != 0 }

/** Decodes [HabitRule.daysOfWeekMask] into the set of days this rule's time window applies on. */
fun HabitRule.daysOfWeekSet(): Set<DayOfWeek> = decodeDaysOfWeek(daysOfWeekMask)

/** Encodes a set of days for storage in [HabitRule.daysOfWeekMask]. An empty set is treated the
 * same as "every day" -- a rule that can never apply on any day isn't a meaningful choice to offer. */
fun encodeDaysOfWeek(days: Set<DayOfWeek>): Int =
    days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }.takeIf { it != 0 } ?: ALL_DAYS_OF_WEEK_MASK

/** Non-printable separator, so it can't collide with a real habit name typed by the user. */
private const val HABIT_NAME_DELIMITER = "\u001F"

/** Non-printable separator for packing multiple target packages into [HabitRule.targetPackages]. */
private const val TARGET_PACKAGE_DELIMITER = "\u001F"

/** Decodes the set of target app packages a rule blocks/unlocks together. Falls back to the legacy
 * single [HabitRule.targetPackageName] for rules created before multi-app support. */
fun HabitRule.targetPackageNames(): List<String> {
    val decoded = targetPackages?.split(TARGET_PACKAGE_DELIMITER)?.map { it.trim() }?.filter { it.isNotBlank() }
    return decoded?.takeIf { it.isNotEmpty() } ?: listOf(targetPackageName).filter { it.isNotBlank() }
}

/** Encodes a list of target packages for storage in [HabitRule.targetPackages]; blanks/duplicates
 * are dropped. Returns null for an empty list. */
fun encodeTargetPackages(packageNames: List<String>): String? =
    packageNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        .takeIf { it.isNotEmpty() }?.joinToString(TARGET_PACKAGE_DELIMITER)

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

/**
 * Marks one [DetectedHabit] name as requiring photo proof before its daily tick counts towards any
 * [HabitRule] -- ticking the box in HabitShare is trivial to fake to yourself, so for habits where
 * that matters, [HabitRuleManager] additionally requires a same-day [HabitProofLog] to exist before
 * treating that habit as "done" for rule-evaluation purposes. Doesn't affect HabitShare itself (we
 * can't touch its own state), only whether *this app's* unlocks are granted.
 *
 * [referencePhotoPath] is the photo taken once, up front, when proof is first required (e.g. "what
 * doing this habit looks like") -- every day's submitted photo is compared against it by
 * [ImageMatcher], and only an on-device visual match is accepted (see [HabitProofActivity]).
 * Non-null whenever [required] is true.
 */
@Entity(tableName = "habit_proof_requirements")
data class HabitProofRequirement(
    @PrimaryKey val habitName: String,
    val required: Boolean = true,
    val referencePhotoPath: String? = null,
)

/** One *approved* (visually matched) proof for [habitName] on [dateEpochDay]. At most one per
 * habit per day -- resubmitting overwrites the same day's row. [note] is optional extra context. */
@Entity(tableName = "habit_proof_logs", primaryKeys = ["habitName", "dateEpochDay"])
data class HabitProofLog(
    val habitName: String,
    val dateEpochDay: Long,
    val photoPath: String,
    val note: String = "",
    val submittedAtMillis: Long,
)
