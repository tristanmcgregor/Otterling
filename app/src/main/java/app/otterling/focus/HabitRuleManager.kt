package app.otterling.focus

import android.content.Context
import app.otterling.content.DashboardConfigStore
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
 *
 * ## Dashboard-driven rules (Phase 5 of `dashboard/SERVER_DRIVEN_CONFIG_PLAN.md`)
 *
 * [rules] additively merges the dashboard's `rules` list (see [dashboardRules]) alongside whatever
 * is in Room, same union approach as Phase 1/2's list managers. Every dashboard rule is modeled as
 * a *time-windowed* rule -- the dashboard wizard always sets a schedule, but the on-device model
 * can't combine a time window with a "daily budget" unlock duration on the same rule, so the
 * dashboard's `dailyBudgetMinutes` field is intentionally not enforced here (it's stored
 * server-side only; revisit if this needs tightening later). Being windowed means these synthetic
 * rules need no persisted grant state ([HabitRule.lastGrantedEpochDay]/[unlockUntilMillis]) --
 * [isTargetUnlocked] re-derives their windowed verdict fresh every call, so there's nothing to
 * write back and no Room row is ever created for them. [evaluateTrigger] already excludes
 * time-windowed rules from its (Room-only, `dao.forTrigger`) grant path, so it needs no changes.
 * A dashboard rule's synthetic [HabitRule.id] is always `<= 0` (Room's autoGenerate primary keys
 * start at 1) -- [isDashboardManaged] and the on-device rule-list UI rely on that to hide
 * edit/enable/remove controls that wouldn't do anything (there's no Room row for them to act on).
 *
 * ## Cross-device rules: global habits + completion reporting
 *
 * Habits themselves are no longer part of this (or any) device's own settings record -- they're
 * a single library shared across the whole fleet (see [DashboardConfigStore.globalHabitsSnapshot],
 * `lockprofile_service.py`'s `HABITS_PATH`), so a rule stored under a DIFFERENT device (e.g. a
 * Mac rule gating an app on that Mac) can reference the exact same habit this phone verifies via
 * HabitShare. [evaluateTrigger] reports every habit HabitShare confirms done today to the server
 * (see [HabitCompletionReporter]) regardless of whether any of THIS phone's own local rules
 * reference it -- the other device's rule evaluation has no other way to see it.
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

    suspend fun rules(): List<HabitRule> = dao.getAll() + dashboardRules()

    /** True if [rule] came from [dashboardRules] rather than Room -- see this class's Phase 5 doc. */
    fun isDashboardManaged(rule: HabitRule): Boolean = rule.id <= 0L

    /** Parses the dashboard's `rules` list into synthetic, always-windowed [HabitRule]s. Never
     *  persisted -- see this class's Phase 5 doc for why that's safe. Skips any entry with no
     *  `targetApps` (a website-only rule, or an older rule saved before this field existed) or no
     *  valid schedule. A rule's `targetApps` can name more than one app -- all of them share the
     *  same [HabitRule.targetPackages], same as a local multi-target rule created via [addRule].
     *  See [dashboardWebsiteRules] for the same entries' website targets: a single rule can carry
     *  both non-empty `targetApps` and `targetWebsites` at once (see api.ts's Rule doc comment on
     *  the dashboard side) -- this method and that one just each pick out their own half. */
    private fun dashboardRules(): List<HabitRule> {
        val snapshot = DashboardConfigStore(context).snapshot() ?: return emptyList()
        val entries = snapshot.optJSONArray("rules") ?: return emptyList()
        // Habits moved to a global library shared across every device (see
        // lockprofile_service.py's LIST_ENDPOINTS comment) -- no longer part of this device's own
        // settings snapshot, so this reads DashboardConfigStore's separate global-habits cache
        // instead of `snapshot.optJSONArray("habits")`.
        val habitNamesById = DashboardConfigStore(context).globalHabitsSnapshot()
            ?.optJSONArray("habits")?.let { habits ->
                (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }
                    .associate { it.optString("id") to it.optString("name") }
            } ?: emptyMap()

        val result = mutableListOf<HabitRule>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val appIds = entry.optJSONArray("targetApps")?.let { apps ->
                (0 until apps.length()).mapNotNull { apps.optJSONObject(it)?.optString("appId")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            if (appIds.isEmpty()) continue
            val schedule = entry.optJSONObject("schedule") ?: continue
            val start = parseTimeToMinuteOfDay(schedule.optString("startTime"))
            val end = parseTimeToMinuteOfDay(schedule.optString("endTime"))
            if (start == null || end == null) continue

            val habitIds = entry.optJSONArray("requiredHabitIds")
            val habitNames = habitIds?.let { ids ->
                (0 until ids.length()).mapNotNull { habitNamesById[ids.optString(it)] }
            } ?: emptyList()

            val daysOfWeek = schedule.optJSONArray("daysOfWeek")?.let { days ->
                (0 until days.length()).mapNotNull { jsDayOfWeekToJava(days.optInt(it, -1)) }.toSet()
            }?.takeIf { it.isNotEmpty() } ?: DayOfWeek.entries.toSet()

            result += HabitRule(
                id = dashboardRuleSyntheticId(entry.optString("id")),
                triggerPackageName = HabitTrackerScanner.HABITSHARE_PACKAGE_NAME,
                targetPackageName = appIds.first(),
                targetPackages = encodeTargetPackages(appIds),
                unlockMinutes = 0,
                habitName = encodeRequiredHabitNames(habitNames),
                windowStartMinute = start,
                windowEndMinute = end,
                daysOfWeekMask = encodeDaysOfWeek(daysOfWeek),
            )
        }
        return result
    }

    /** Room's autoGenerate primary keys start at 1, so any id `<= 0` is guaranteed to never
     *  collide with a real local rule -- see [isDashboardManaged]. */
    private fun dashboardRuleSyntheticId(dashboardId: String): Long =
        -(1L + (dashboardId.hashCode().toLong() and 0x7FFFFFFFL))

    /**
     * Domain-targeted counterpart to [dashboardRules]'s app-targeted rules (a rule's
     * `targetWebsites` entries, see lockprofile_service.py's `_build_rule` for that field) -- same
     * "dashboard wizard always sets a schedule" design as [dashboardRules], so this only ever
     * needs the windowed-rule shape, not the full [HabitRule] entity a website has no meaningful
     * equivalent for (no package to suspend). Never persisted, parsed fresh on every call, same
     * as [dashboardRules]'s own synthetic rules -- the list realistically only has a handful of
     * entries, so re-parsing the JSON on every DNS query (see [isWebsiteCurrentlyBlocked]) is
     * cheap enough not to need caching. One JSON rule with N domains in `targetWebsites` expands
     * to N [WebsiteHabitRule]s here, all sharing that rule's schedule/habit condition -- mirroring
     * [dashboardRules] collapsing a rule's `targetApps` onto one [HabitRule.targetPackages]
     * instead, since [HabitRule] already models multi-target but this simpler struct doesn't.
     */
    private data class WebsiteHabitRule(
        val domain: String,
        val requiredHabitNames: List<String>,
        val windowStartMinute: Int,
        val windowEndMinute: Int,
        val daysOfWeek: Set<DayOfWeek>,
    )

    private fun dashboardWebsiteRules(): List<WebsiteHabitRule> {
        val snapshot = DashboardConfigStore(context).snapshot() ?: return emptyList()
        val entries = snapshot.optJSONArray("rules") ?: return emptyList()
        val habitNamesById = DashboardConfigStore(context).globalHabitsSnapshot()
            ?.optJSONArray("habits")?.let { habits ->
                (0 until habits.length()).mapNotNull { habits.optJSONObject(it) }
                    .associate { it.optString("id") to it.optString("name") }
            } ?: emptyMap()

        val result = mutableListOf<WebsiteHabitRule>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val domains = entry.optJSONArray("targetWebsites")?.let { sites ->
                (0 until sites.length()).mapNotNull { sites.optJSONObject(it)?.optString("domain")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            if (domains.isEmpty()) continue
            val schedule = entry.optJSONObject("schedule") ?: continue
            val start = parseTimeToMinuteOfDay(schedule.optString("startTime")) ?: continue
            val end = parseTimeToMinuteOfDay(schedule.optString("endTime")) ?: continue

            val habitIds = entry.optJSONArray("requiredHabitIds")
            val habitNames = habitIds?.let { ids ->
                (0 until ids.length()).mapNotNull { habitNamesById[ids.optString(it)] }
            } ?: emptyList()

            val daysOfWeek = schedule.optJSONArray("daysOfWeek")?.let { days ->
                (0 until days.length()).mapNotNull { jsDayOfWeekToJava(days.optInt(it, -1)) }.toSet()
            }?.takeIf { it.isNotEmpty() } ?: DayOfWeek.entries.toSet()

            domains.forEach { domain ->
                result += WebsiteHabitRule(domain.lowercase(), habitNames, start, end, daysOfWeek)
            }
        }
        return result
    }

    /**
     * True if [hostname] (or a parent domain) is currently blocked by a dashboard-configured
     * website habit rule -- inside that rule's time window with its required habit(s) not done
     * today (empty [WebsiteHabitRule.requiredHabitNames] means "no habit condition, blocked
     * unconditionally for the whole window", same semantics as [isWindowedRuleSatisfied]).
     * Consulted by `VpnFilterService.handleDnsPacket` the same way
     * `DomainBlocklistManager.isCustomBlocked`'s guardian-set `blockedWebsites` is -- always-on,
     * not gated on proxy availability, since this is an explicit guardian-authored rule, not a
     * coarse fallback list. Returns `false` immediately (before touching Room) when there are no
     * website rules configured at all, so a phone with none set up pays no per-DNS-query cost
     * beyond the cheap JSON re-parse above.
     */
    suspend fun isWebsiteCurrentlyBlocked(hostname: String): Boolean {
        val rules = dashboardWebsiteRules()
        if (rules.isEmpty()) return false
        val today = LocalDate.now().toEpochDay()
        val rawDoneNames = detectedHabitDao.getAll()
            .filter { it.dateEpochDay == today && it.doneToday }
            .map { it.name.lowercase() }
            .toSet()
        val doneHabitNamesToday = proofManager.filterSatisfied(rawDoneNames)
        val nowMinuteOfDay = currentMinuteOfDay()
        val nowDayOfWeek = LocalDate.now().dayOfWeek
        val host = hostname.lowercase().trimEnd('.')
        return rules.any { rule ->
            domainMatches(host, rule.domain) &&
                nowDayOfWeek in rule.daysOfWeek &&
                isWithinWindow(nowMinuteOfDay, rule.windowStartMinute, rule.windowEndMinute) &&
                !isWebsiteRuleSatisfied(rule, doneHabitNamesToday)
        }
    }

    private fun isWebsiteRuleSatisfied(rule: WebsiteHabitRule, doneHabitNamesToday: Set<String>): Boolean {
        if (rule.requiredHabitNames.isEmpty()) return false
        return rule.requiredHabitNames.all { requiredName ->
            val needle = requiredName.lowercase()
            doneHabitNamesToday.any { it.contains(needle) || needle.contains(it) }
        }
    }

    /** True if [hostname] is [ruleDomain] or a subdomain of it -- same walk-up-parent-domains
     *  matching [DomainBlocklistManager.isBlocked]/[CustomBlocklistManager.hostMatches] use. */
    private fun domainMatches(hostname: String, ruleDomain: String): Boolean {
        var candidate = hostname
        while (candidate.isNotEmpty()) {
            if (candidate == ruleDomain) return true
            val dotIndex = candidate.indexOf('.')
            if (dotIndex == -1) break
            candidate = candidate.substring(dotIndex + 1)
        }
        return false
    }

    /** "HH:MM" -> minutes since midnight, or null if malformed. */
    private fun parseTimeToMinuteOfDay(text: String): Int? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** JS `Date.getDay()` convention (0=Sunday..6=Saturday, used by the dashboard) to
     *  [DayOfWeek] (1=Monday..7=Sunday). Null for an out-of-range value. */
    private fun jsDayOfWeekToJava(jsDay: Int): DayOfWeek? = when (jsDay) {
        0 -> DayOfWeek.SUNDAY
        in 1..6 -> DayOfWeek.of(jsDay)
        else -> null
    }

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
     * that just lost its last (or only, now-dropped) rule would otherwise stay suspended forever.
     * Checks against [rules] (Room + dashboard), not just Room, so a target still governed by a
     * dashboard rule doesn't get unsuspended out from under it by an unrelated local edit. */
    private suspend fun unsuspendOrphanedTargets(oldTargets: Collection<String>, newTargets: Collection<String>) {
        val removed = oldTargets.toSet() - newTargets.toSet()
        if (removed.isEmpty()) return
        val stillGoverned = rules().flatMap { it.targetPackageNames() }.toSet()
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

        val rawDoneNames = detectedHabitRows.filter { it.second }.map { it.first.lowercase() }.toSet()
        val doneHabitNames = proofManager.filterSatisfied(rawDoneNames)
        // Reported regardless of whether any of THIS phone's own local (Room) rules reference
        // these habits -- a dashboard rule gating a different device (e.g. a Mac rule) has no
        // local representation here to condition this on. See HabitCompletionReporter's doc
        // comment. Must run before the early return below, which only applies to the
        // local-rule-granting logic that follows.
        HabitCompletionReporter(context).reportDoneToday(doneHabitNames)

        val candidates = dao.forTrigger(triggerPackageName)
            .filter { it.lastGrantedEpochDay != today && !it.isTimeWindowed() }
        if (candidates.isEmpty()) return@withLock 0
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

    /** Unsuspends every target app referenced by any rule (Room + dashboard) -- used when
     *  protection is turned off. */
    suspend fun unsuspendAllTargets() {
        rules()
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
        rules().forEach { rule ->
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
