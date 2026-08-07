package app.otterling.focus

import android.content.Context
import app.otterling.data.AppDatabase
import app.otterling.restrictions.PackageBlockEnforcer
import app.otterling.tamper.TamperEventLogger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small command system: "app B is blocked until (habit(s) are done in app A), then it unlocks
 * for N minutes", with as many independent rules as you like, and as many required habits per rule
 * as you like (all of them must be done for that rule to fire -- see [HabitRule.requiredHabitNames]).
 * A rule can optionally be time-windowed (see [HabitRule.isTimeWindowed]) so it only enforces
 * blocking during a specific time-of-day range, e.g. "block unless 'Bible AM' done, but only from
 * midnight to 9pm" alongside a second rule for "Bible PM" covering 9pm to midnight. Detection
 * itself comes from the HabitShare REST API (see [HabitShareSyncManager]) -- this class just lets
 * that trigger fan out to many trigger-app/target-app/duration/window combinations instead of one
 * hardcoded one. Any habit name marked in [HabitProofManager] as
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
class HabitRuleManager(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).habitRuleDao()
    private val detectedHabitDao = AppDatabase.getInstance(context).detectedHabitDao()
    private val proofManager = HabitProofManager(context)
    private val tamperEventLogger = TamperEventLogger(context)

    // Accessibility content-changed events can fire in a quick burst, and each scan calls
    // evaluateTrigger() from its own coroutine without waiting for a prior one to finish. Without
    // serializing here, two overlapping calls could both read the same rule (lastGrantedEpochDay
    // != today, unlockUntilMillis = X) before either had written its grant, both decide to fire,
    // and race to write -- a classic lost-update: whichever write lands second silently clobbers
    // the first, and depending on timing that can leave the grant computed from a stale base
    // instead of correctly extending once. Guarding the whole read-decide-write sequence makes
    // concurrent scans of the same trigger safe to fire from, matching the "idempotent, safe to
    // call on every scan" guarantee this class already documents.
    private val evaluationMutex = Mutex()

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
        targetPackageNames: List<String>,
        unlockMinutes: Int,
        requiredHabitNames: List<String> = emptyList(),
        windowStartMinute: Int? = null,
        windowEndMinute: Int? = null,
        daysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) {
        val targets = targetPackageNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        dao.insert(
            HabitRule(
                triggerPackageName = triggerPackageName,
                targetPackageName = targets.first(),
                targetPackages = encodeTargetPackages(targets),
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
     * condition that may no longer exist. Any target app dropped by this edit that no longer has
     * another rule governing it is explicitly unsuspended rather than left stuck blocked with
     * nothing left to ever unlock it.
     */
    suspend fun updateRule(
        id: Long,
        triggerPackageName: String,
        targetPackageNames: List<String>,
        unlockMinutes: Int,
        requiredHabitNames: List<String> = emptyList(),
        windowStartMinute: Int? = null,
        windowEndMinute: Int? = null,
        daysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ) {
        val existing = dao.getAll().find { it.id == id } ?: return
        val targets = targetPackageNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        dao.update(
            existing.copy(
                triggerPackageName = triggerPackageName,
                targetPackageName = targets.first(),
                targetPackages = encodeTargetPackages(targets),
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
        unsuspendOrphanedTargets(oldTargets = existing.targetPackageNames(), newTargets = targets)
    }

    suspend fun deleteRule(id: Long) {
        val oldTargets = dao.getAll().find { it.id == id }?.targetPackageNames().orEmpty()
        dao.delete(id)
        reapplyAll()
        unsuspendOrphanedTargets(oldTargets = oldTargets, newTargets = emptyList())
    }

    /** Any package in [oldTargets] that no longer has any rule pointing at it should no longer be
     * blocked -- reapplyAll() only visits packages that still have at least one rule, so a package
     * that just lost its last (or only, now-dropped) rule would otherwise stay suspended forever. */
    private suspend fun unsuspendOrphanedTargets(oldTargets: Collection<String>, newTargets: Collection<String>) {
        val removed = oldTargets.toSet() - newTargets.toSet()
        if (removed.isEmpty()) return
        val stillGoverned = dao.getAll().flatMap { it.targetPackageNames() }.toSet()
        removed.forEach { pkg -> if (pkg !in stillGoverned) setSuspended(pkg, suspended = false) }
    }

    /**
     * Called with the latest [detectedHabitRows] (habit name + done-today pairs sourced from the
     * HabitShare REST API via [HabitShareSyncManager]). Grants (at most once per calendar day, per
     * rule) every enabled, non-time-windowed rule whose trigger matches and whose condition is met,
     * and returns how many fired -- useful for a "you just unlocked N app(s)" toast. Idempotent:
     * safe to call on every sync. Time-windowed rules are deliberately excluded here -- they're
     * continuously re-derived by [reapplyAll] instead, since "done" can happen hours before the
     * window that cares about it even starts.
     */
    suspend fun evaluateTrigger(
        triggerPackageName: String,
        detectedHabitRows: List<Pair<String, Boolean>>,
    ): Int = evaluationMutex.withLock {
        val today = LocalDate.now().toEpochDay()
        val candidates = dao.forTrigger(triggerPackageName)
            .filter { it.lastGrantedEpochDay != today && !it.isTimeWindowed() }
        if (candidates.isEmpty()) return@withLock 0

        val rawDoneNames = detectedHabitRows.filter { it.second }.map { it.first.lowercase() }.toSet()
        val doneHabitNames = proofManager.filterSatisfied(rawDoneNames)
        // "All habits complete" (a non-windowed rule with no named habits) is derived directly from
        // the API rows: it fires only when there's at least one detected habit and every detected
        // row is both done and proof-satisfied.
        val allComplete = detectedHabitRows.isNotEmpty() &&
            detectedHabitRows.all { it.second && it.first.lowercase() in doneHabitNames }

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
            val condition = required.takeIf { it.isNotEmpty() }?.joinToString()
                ?: "all habits"
            tamperEventLogger.log(
                type = "HABIT_UNLOCK",
                details = "Unlocked ${rule.targetPackageNames().joinToString()} for ${rule.unlockMinutes} minutes after $condition.",
            )
            grantedCount++
        }
        if (grantedCount > 0) reapplyAll()
        grantedCount
    }

    /** Unsuspends every target app referenced by any rule -- used when protection is turned off. */
    suspend fun unsuspendAllTargets() {
        dao.getAll()
            .flatMap { it.targetPackageNames() }
            .distinct()
            .forEach { setSuspended(it, suspended = false) }
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

        val rulesByPackage = mutableMapOf<String, MutableList<HabitRule>>()
        dao.getAll().forEach { rule ->
            rule.targetPackageNames().forEach { packageName ->
                rulesByPackage.getOrPut(packageName) { mutableListOf() }.add(rule)
            }
        }
        rulesByPackage.forEach { (packageName, allRulesForTarget) ->
            val activeRules = allRulesForTarget.filter { it.enabled }
            val unlocked = activeRules.isEmpty() ||
                isTargetUnlocked(activeRules, now, nowMinuteOfDay, nowDayOfWeek, doneHabitNamesToday)
            setSuspended(packageName, suspended = !unlocked)
        }
    }

    /**
     * A target is blocked if ANY of its currently-in-window windowed rules has an unmet condition
     * -- that takes priority over everything else. The old logic OR'd every rule's own
     * "unlocked?" verdict together, where an out-of-window (or simply non-windowed-and-not-yet-
     * triggered) rule trivially counts as "unlocked" by default; with two or more windowed rules
     * on the same target whose windows overlap or nest (e.g. a hard "no phone before 9am" window
     * sitting inside a broader all-day "unless Bible AM done" window), that let the broader rule's
     * satisfied condition silently override the narrower one's currently-active block. Splitting
     * windowed rules out and requiring ALL of them (that are currently in-window) to be satisfied
     * fixes that, while non-windowed "unlock for N minutes" rules keep their original semantics
     * among themselves -- any ONE of several independent habit-unlock paths for the same target is
     * still enough, as long as no currently-active window rule is vetoing it.
     */
    private fun isTargetUnlocked(
        rules: List<HabitRule>,
        now: Long,
        nowMinuteOfDay: Int,
        nowDayOfWeek: DayOfWeek,
        doneHabitNamesToday: Set<String>,
    ): Boolean {
        val (windowed, nonWindowed) = rules.partition { it.isTimeWindowed() }
        val windowedBlock = windowed.any { rule ->
            isCurrentlyInWindow(rule, nowMinuteOfDay, nowDayOfWeek) &&
                !isWindowedRuleSatisfied(rule, doneHabitNamesToday)
        }
        if (windowedBlock) return false
        if (nonWindowed.isEmpty()) return true
        return nonWindowed.any { it.unlockUntilMillis > now }
    }

    /** A day/time this windowed rule's window doesn't cover (including a day not in
     * [HabitRule.daysOfWeekSet]) behaves as if the window simply hasn't started. */
    private fun isCurrentlyInWindow(rule: HabitRule, nowMinuteOfDay: Int, nowDayOfWeek: DayOfWeek): Boolean {
        val start = rule.windowStartMinute ?: return false
        val end = rule.windowEndMinute ?: return false
        if (nowDayOfWeek !in rule.daysOfWeekSet()) return false
        return isWithinWindow(nowMinuteOfDay, start, end)
    }

    private fun isWindowedRuleSatisfied(rule: HabitRule, doneHabitNamesToday: Set<String>): Boolean {
        val required = rule.requiredHabitNames()
        // Empty here means "no habit condition at all" for a windowed rule (see HabitRule) --
        // unlike the non-windowed "any/all habits complete" pattern, there's no condition to ever
        // satisfy, so it's simply blocked for the entire window, every day.
        if (required.isEmpty()) return false
        return required.all { requiredName ->
            val needle = requiredName.lowercase()
            doneHabitNamesToday.any { it.contains(needle) || needle.contains(it) }
        }
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
        PackageBlockEnforcer.setBlocked(context, packageName, blocked = suspended)
    }
}
