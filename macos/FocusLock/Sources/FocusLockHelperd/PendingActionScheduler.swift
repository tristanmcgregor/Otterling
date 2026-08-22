import Foundation
import FocusLockShared

/// Shared "queue a protection-reducing action for the cooldown, or apply it inline when the
/// cooldown is zero" logic -- split out of what used to be `XPCService.schedule` so a second,
/// differently-authorized caller (`DashboardConfigSync`) can reuse it without going through
/// `XPCService.authorize`'s passcode/admin-UID check, which is specific to a local, interactive
/// XPC connection and doesn't apply to a background sync process with no caller UID to check.
///
/// This file owns only what happens AFTER a caller is already authorized by whatever means is
/// appropriate for it -- `XPCService` for a local admin (+ optional passcode), `DashboardConfigSync`
/// for possession of the `LOCKPROFILE_TOKEN` bearer (see that file's doc comment for why that's
/// the accepted, explicitly-chosen tradeoff). Every action still queues through the same
/// `PendingAction` + `cooldownHours` mechanism and fires the same `TamperReporter` audit trail
/// regardless of which principal triggered it -- only the `source` tag differs, so the activity
/// log always shows who asked for a protection-reducing change.
enum PendingActionScheduler {
    static func schedule(
        _ kind: PendingActionKind,
        target: String = "",
        source: String,
        stateStore: StateStore,
        onScheduled: () -> Void
    ) -> FocusLockResult {
        let state = stateStore.snapshot()

        if state.pendingActions.contains(where: { $0.kind == kind && $0.target == target }) {
            return .denied("That change is already scheduled. Check `focuslockctl status` for when it takes effect.")
        }

        let now = Date()
        let action = PendingAction(
            kind: kind,
            target: target,
            requestedAt: now,
            effectiveAt: now.addingTimeInterval(state.cooldownHours * 3600)
        )

        TamperReporter.report(type: "pending_action_requested", details: "\(action.describedFully) (source: \(source))")

        guard state.cooldownHours > 0 else {
            PendingActionApplier.apply(action, stateStore: stateStore)
            onScheduled()
            return .ok
        }

        stateStore.mutate { $0.pendingActions.append(action) }
        onScheduled()

        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return FocusLockResult(
            success: true,
            message: "Scheduled: \(action.describedFully). Takes effect \(formatter.string(from: action.effectiveAt)). "
                + "Anyone can cancel it before then with `focuslockctl cancel \(action.id)`."
        )
    }
}
