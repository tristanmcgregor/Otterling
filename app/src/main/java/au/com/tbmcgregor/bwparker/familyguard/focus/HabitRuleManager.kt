package au.com.tbmcgregor.bwparker.familyguard.focus

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import au.com.tbmcgregor.bwparker.familyguard.admin.DeviceAdminReceiverImpl
import au.com.tbmcgregor.bwparker.familyguard.data.AppDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * A small command system: "app B is blocked until (habit(s) are done in app A), then it unlocks
 * for N minutes", with as many independent rules as you like, and as many required habits per rule
 * as you like (all of them must be done for that rule to fire -- see [HabitRule.requiredHabitNames]).
 * A rule can optionally be time-windowed (see [HabitRule.isTimeWindowed]) so it only enforces
 * blocking during a specific time-of-day range, e.g. "block unless 'Bible AM' done, but only from
 * midnight to 9pm" alongside a second rule for "Bible PM" covering 9pm to midnight. Detection
 * itself is still the same on-screen heuristic [FocusGuardAccessibilityService] already uses --
 * this class just lets that trigger fan out to many trigger-app/target-app/duration/window
 * combinations instead of one hardcoded one. Any habit name marked in [HabitProofManager] as
 * requiring proof only counts as "done" here once a same-day [HabitProofLog] also exists --
 * see [HabitProofManager.filterSatisfied].
 *
 * Every [targetPackageName] referenced by a rule is treated like a [RewardApp]: suspended by
 * default, and only unsuspended while at least one of its *enabled* rules is currently satisfied.
 * [reapplyAll] re-derives and re-asserts that suspended state from scratch every time it's called,
 * so it's safe (and expected) to call it periodically for drift recovery, exactly like the other
 * enforcement managers -- this is also what makes time-windowed rules transition on time even
 * without the trigger app being reopened, since it's called every few minutes regardless.
 */
class HabitRuleManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).habitRuleDao()
    private val detectedHabitDao = AppDatabase.getInstance(context).detectedHabitDao()
    private val proofManager = HabitProofManager(context)
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val adminComponent = ComponentName(context, DeviceAdminReceiverImpl::class.java)

    suspend fun rules(): List<HabitRule> = dao.getAll()

    /**
     * [requiredHabitNames] empty means "any/all habits complete" (the original whole-tracker
     * pattern) for a non-windowed rule, or "no habit condition at all -- just block for the whole
     * window" for a windowed one (see [HabitRule]); otherwise the rule only fires once every
     * listed habit is done today.
     *
     * [windowStartMinute]/[windowEndMinute] (both minutes-since-midnight 0..1439, or both left
     * null) make this a time-windowed rule instead of the "unlock for [unlockMinutes] once done"
     * model -- see [HabitRule] for exact semantics. Unlike a non-windowed rule, "all habits done"
     * has no persisted signal [reapplyAll] can check outside of a live scan, so a windowed rule's
     * [requiredHabitNames] must either be empty (pure time block) or name specific habits.
     *
     * [daysOfWeek] restricts a windowed rule to only those days (defaults to every day); ignored
     * for non-windowed rules.
     */
    suspend fun addRule(
        triggerPackageName: String,
        targetPackageName: String,
        unlockMinutes: Int,
        requiredHabitNames: List<String> = emptyList(),
        windowStartMinute: Int? = null,
        windowEndMinute: Int? = null,
        daysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) {
        dao.insert(
            HabitRule(
                triggerPackageName = triggerPackageName,
                targetPackageName = targetPackageName,
                unlockMinutes = unlockMinutes,
                habitName = encodeRequiredHabitNames(requiredHabitNames),
                windowStartMinute = windowStartMinute,
                windowEndMinute = windowEndMinute,
                daysOfWeekMask = encodeDaysOfWeek(daysOfWeek),
            ),
        )
        reapplyAll()
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.getAll().find { it.id == id }?.let { dao.update(it.copy(enabled = enabled)) }
        reapplyAll()
    }

    /**
     * Replaces every field of an existing rule in place (used by the "Edit rule" flow) and
     * resets its grant state, since the old unlock/last-granted state was derived from a
     * condition that may no longer exist. If [targetPackageName] changes and no other rule still
     * governs the old target, that app is explicitly unsuspended rather than left stuck blocked
     * with nothing left to ever unlock it.
     */
    suspend fun updateRule(
        id: Long,
        triggerPackageName: String,
        targetPackageName: String,
        unlockMinutes: Int,
        requiredHabitNames: List<String> = emptyList(),
        windowStartMinute: Int? = null,
        windowEndMinute: Int? = null,
        daysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) {
        val existing = dao.getAll().find { it.id == id } ?: return
        dao.update(
            existing.copy(
                triggerPackageName = triggerPackageName,
                targetPackageName = targetPackageName,
                unlockMinutes = unlockMinutes,
                habitName = encodeRequiredHabitNames(requiredHabitNames),
                windowStartMinute = windowStartMinute,
                windowEndMinute = windowEndMinute,
                daysOfWeekMask = encodeDaysOfWeek(daysOfWeek),
                lastGrantedEpochDay = -1,
                unlockUntilMillis = 0,
            ),
        )
        reapplyAll()
        unsuspendOrphanedTarget(oldTarget = existing.targetPackageName, newTarget = targetPackageName)
    }

    suspend fun deleteRule(id: Long) {
        val oldTarget = dao.getAll().find { it.id == id }?.targetPackageName
        dao.delete(id)
        reapplyAll()
        unsuspendOrphanedTarget(oldTarget = oldTarget, newTarget = null)
    }

    /** If [oldTarget] no longer has any rule pointing at it, it should no longer be blocked --
     * reapplyAll() only visits targets that still have at least one rule, so a target that just
     * lost its last (or only, now-retargeted) rule would otherwise stay suspended forever. */
    private suspend fun unsuspendOrphanedTarget(oldTarget: String?, newTarget: String?) {
        if (oldTarget == null || oldTarget == newTarget) return
        val stillGoverned = dao.getAll().any { it.targetPackageName == oldTarget }
        if (!stillGoverned) setSuspended(oldTarget, suspended = false)
    }

    /**
     * Called by the accessibility service once it has scanned [triggerPackageName]'s screen into
     * [texts] (whole-screen text, for the "all complete" pattern) and [detectedHabitRows] (name +
     * done-today pairs from [HabitTrackerScanner], for single-habit rules). Grants (at most once
     * per calendar day, per rule) every enabled, non-time-windowed rule whose trigger matches and
     * whose condition is met, and returns how many fired -- useful for a "you just unlocked N
     * app(s)" toast. Idempotent: safe to call on every scan. Time-windowed rules are deliberately
     * excluded here -- they're continuously re-derived by [reapplyAll] instead, since "done" can
     * happen hours before the window that cares about it even starts.
     */
    suspend fun evaluateTrigger(
        triggerPackageName: String,
        texts: List<String>,
        detectedHabitRows: List<Pair<String, Boolean>>,
    ): Int {
        val today = LocalDate.now().toEpochDay()
        val candidates = dao.forTrigger(triggerPackageName)
            .filter { it.lastGrantedEpochDay != today && !it.isTimeWindowed() }
        if (candidates.isEmpty()) return 0

        val allComplete = looksLikeAllComplete(texts)
        val rawDoneNames = detectedHabitRows.filter { it.second }.map { it.first.lowercase() }.toSet()
        val doneHabitNames = proofManager.filterSatisfied(rawDoneNames)

        val now = System.currentTimeMillis()
        var grantedCount = 0
        candidates.forEach { rule ->
            val required = rule.requiredHabitNames()
            val fires = if (required.isEmpty()) {
                allComplete
            } else {
                required.all { requiredName ->
                    val needle = requiredName.lowercase()
                    doneHabitNames.any { it.contains(needle) || needle.contains(it) }
                }
            }
            if (!fires) return@forEach
            val newUntil = maxOf(rule.unlockUntilMillis, now) + rule.unlockMinutes * 60_000L
            dao.update(rule.copy(lastGrantedEpochDay = today, unlockUntilMillis = newUntil))
            grantedCount++
        }
        if (grantedCount > 0) reapplyAll()
        return grantedCount
    }

    /** Re-derives suspended state for every target app from scratch. Call periodically -- this is
     * also what makes time-windowed rules' blocking track the clock. */
    suspend fun reapplyAll() {
        val now = System.currentTimeMillis()
        val nowMinuteOfDay = currentMinuteOfDay()
        val nowDayOfWeek = LocalDate.now().dayOfWeek
        val today = LocalDate.now().toEpochDay()
        val rawDoneHabitNamesToday = detectedHabitDao.getAll()
            .filter { it.dateEpochDay == today && it.doneToday }
            .map { it.name.lowercase() }
            .toSet()
        val doneHabitNamesToday = proofManager.filterSatisfied(rawDoneHabitNamesToday)

        dao.getAll().groupBy { it.targetPackageName }.forEach { (packageName, allRulesForTarget) ->
            val activeRules = allRulesForTarget.filter { it.enabled }
            val unlocked = activeRules.isEmpty() ||
                activeRules.any { isRuleUnlocked(it, now, nowMinuteOfDay, nowDayOfWeek, doneHabitNamesToday) }
            setSuspended(packageName, suspended = !unlocked)
        }
    }

    private fun isRuleUnlocked(
        rule: HabitRule,
        now: Long,
        nowMinuteOfDay: Int,
        nowDayOfWeek: DayOfWeek,
        doneHabitNamesToday: Set<String>,
    ): Boolean {
        val start = rule.windowStartMinute
        val end = rule.windowEndMinute
        if (start != null && end != null) {
            // A day this rule's window doesn't apply to behaves as if the window never started.
            if (nowDayOfWeek !in rule.daysOfWeekSet()) return true
            if (!isWithinWindow(nowMinuteOfDay, start, end)) return true
            val required = rule.requiredHabitNames()
            // Empty here means "no habit condition at all" for a windowed rule (see HabitRule) --
            // unlike the non-windowed "any/all habits complete" pattern, there's no condition to
            // ever satisfy, so it's simply blocked for the entire window, every day.
            if (required.isEmpty()) return false
            return required.all { requiredName ->
                val needle = requiredName.lowercase()
                doneHabitNamesToday.any { it.contains(needle) || needle.contains(it) }
            }
        }
        return rule.unlockUntilMillis > now
    }

    /** [end] of 0 means "through to midnight" (i.e. wraps around); handles overnight windows like
     * 9pm-6am the same way. */
    private fun isWithinWindow(minuteOfDay: Int, start: Int, end: Int): Boolean =
        if (start <= end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end

    private fun currentMinuteOfDay(): Int {
        val time = LocalTime.now()
        return time.hour * 60 + time.minute
    }

    private fun setSuspended(packageName: String, suspended: Boolean) {
        val dpm = devicePolicyManager ?: return
        try {
            dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), suspended)
        } catch (error: SecurityException) {
            Log.e(TAG, "Not authorized to suspend $packageName", error)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Cannot suspend $packageName (not installed?)", error)
        }
    }

    /** Matches common "3/3" or "3 of 3" completion-counter phrasing where done == total > 0. */
    private fun looksLikeAllComplete(texts: List<String>): Boolean {
        val pattern = Regex("""(\d+)\s*(?:/|of)\s*(\d+)""", RegexOption.IGNORE_CASE)
        return texts.any { text ->
            val match = pattern.find(text) ?: return@any false
            val (done, total) = match.destructured
            val totalValue = total.toIntOrNull() ?: return@any false
            totalValue > 0 && done.toIntOrNull() == totalValue
        }
    }

    private companion object {
        const val TAG = "HabitRuleManager"
    }
}
