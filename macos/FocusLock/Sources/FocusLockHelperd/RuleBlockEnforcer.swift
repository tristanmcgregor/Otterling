import Foundation
import FocusLockShared

/// Live, ephemeral evaluation of dashboard-authored rules (`MacRule`, see that struct's doc
/// comment) against the global habit-completion state (`GlobalHabit`) -- computes, fresh on
/// every call, which executable names should CURRENTLY be blocked because their governing
/// rule's schedule window is active and at least one required habit isn't done today.
///
/// Deliberately separate from `blockedApps`/`AppBlockEnforcer`'s existing gated-removal model
/// (see this project's plan doc's "Key design decision"): a rule-driven block must lift the
/// instant its condition is satisfied, with no cooldown and no guardian action -- mirroring
/// Android's own `HabitRuleManager.reapplyAll()`/`isTargetUnlocked`, which do exactly the same
/// thing, symmetrically and immediately, on their own ~5-minute cadence. Nothing computed here
/// is ever persisted to `FocusLockState` -- it's recomputed from `dashboardConfigCache?.rules` +
/// `globalHabitsCache` every time `EnforcementLoop`'s tick calls this.
enum RuleBlockEnforcer {
    /// Returns the executable names that should currently be blocked. The self-block guard
    /// (`AppBlockEnforcer.protectedExecutables`) is NOT applied here -- `EnforcementLoop` merges
    /// this result into the same `AppBlockEnforcer.enforce` call that already guards against it
    /// for `blockedApps`, so it's applied exactly once, in one place.
    static func currentlyBlockedExecutableNames(state: FocusLockState) -> Set<String> {
        guard let rules = state.dashboardConfigCache?.rules, !rules.isEmpty else { return [] }
        let habitsById = Dictionary(state.globalHabitsCache.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })

        let calendar = Calendar.current
        let components = calendar.dateComponents([.hour, .minute, .weekday], from: Date())
        let nowMinuteOfDay = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        // Calendar's `.weekday` is always 1=Sunday...7=Saturday (Gregorian, locale-independent --
        // NOT affected by `firstWeekday`), so subtracting 1 converts to the dashboard's own JS
        // `Date.getDay()` convention (0=Sunday...6=Saturday) that MacRule.daysOfWeek already uses.
        let nowJSWeekday = (components.weekday ?? 1) - 1

        var blocked: Set<String> = []
        for rule in rules {
            guard rule.daysOfWeek.contains(nowJSWeekday),
                  isWithinWindow(nowMinuteOfDay, start: rule.windowStartMinute, end: rule.windowEndMinute) else {
                continue
            }
            let satisfied = rule.requiredHabitIds.allSatisfy { habitsById[$0]?.doneToday == true }
            if !satisfied {
                blocked.insert(rule.executableName)
            }
        }
        return blocked
    }

    /// Mirrors Android's `HabitRuleManager.isWithinWindow` exactly, including the overnight
    /// (e.g. 21:00-06:00) wraparound case.
    private static func isWithinWindow(_ minuteOfDay: Int, start: Int, end: Int) -> Bool {
        start <= end ? (minuteOfDay >= start && minuteOfDay < end) : (minuteOfDay >= start || minuteOfDay < end)
    }
}
