import Foundation
import FocusLockShared

/// Applies `PendingAction`s once their cooldown has elapsed. Split out of `XPCService` because two
/// very different callers need the same logic: `EnforcementLoop` (the normal path -- a matured
/// action, applied on a timer tick with no one connected) and `XPCService` itself (the degenerate
/// `cooldownHours == 0` path, where the request is applied inline).
///
/// Every mutation here is the protection-*reducing* half of a pair whose protection-increasing half
/// lives ungated in `XPCService`. Nothing in this file is reachable without having already cleared
/// the passcode gate at request time.
enum PendingActionApplier {
    /// Applies one action against state. Side effects that touch the system (unlocking a protected
    /// bundle, tearing down DNS) happen here rather than at request time, so a cancelled action
    /// leaves no trace.
    static func apply(_ action: PendingAction, stateStore: StateStore) {
        switch action.kind {
        case .removeBlockedApp:
            stateStore.mutate { state in
                state.blockedApps.removeAll { $0.executableName == action.target }
            }

        case .removeBlockedDomain:
            stateStore.mutate { state in
                state.blockedDomains.removeAll { $0 == action.target }
            }

        case .removeProtectedApp:
            // Read the bundle path out of state before dropping the entry -- once it's gone there's
            // nothing left to tell us which bundle to unlock, and it would stay `schg` forever.
            let bundlePath = stateStore.snapshot().protectedApps
                .first { $0.executableName == action.target }?.bundlePath
            if let bundlePath {
                AppProtector.unlock(bundlePath: bundlePath)
            }
            stateStore.mutate { state in
                state.protectedApps.removeAll { $0.executableName == action.target }
            }

        case .disableDNSEnforcement:
            stateStore.mutate { state in
                state.dnsEnforcementEnabled = false
            }
            DNSEnforcer.remove()

        case .disableProxyEnforcement:
            stateStore.mutate { state in
                state.proxyEnforcementEnabled = false
                // The firewall force-through only makes sense while the proxy is enforced, so tearing
                // down the proxy takes it down too -- otherwise pf would keep dropping direct :443
                // with no proxy to route through, i.e. take web offline (the exact failure we forbid).
                state.forceProxyViaFirewall = false
            }
            ProxyEnforcer.remove()

        case .setCloudFilterHost:
            stateStore.mutate { state in
                state.cloudFilterHost = action.target
            }
            reapplyDNSIfEnforcing(stateStore: stateStore)

        case .disableCloudFilter:
            stateStore.mutate { state in
                state.cloudFilterEnabled = false
            }
            reapplyDNSIfEnforcing(stateStore: stateStore)

        case .lowerCooldownHours:
            guard let hours = Double(action.target) else { break }
            stateStore.mutate { state in
                state.cooldownHours = max(0, min(hours, FocusLockConstants.maximumCooldownHours))
            }

        case .clearPasscode:
            stateStore.mutate { state in
                state.guardianPasscode = nil
                state.passcodeConfigured = false
            }
        }
    }

    /// Applies every action whose `effectiveAt` has passed and drops it from the queue. Returns the
    /// applied actions so the caller can log/report them; an empty return means nothing was due.
    ///
    /// Each action is removed from the queue *before* being applied, so an action that somehow
    /// throws or wedges can't be retried forever on every subsequent tick.
    static func applyMatured(stateStore: StateStore, now: Date = Date()) -> [PendingAction] {
        let due = stateStore.snapshot().pendingActions.filter { $0.isMature(asOf: now) }
        guard !due.isEmpty else { return [] }

        let dueIDs = Set(due.map(\.id))
        stateStore.mutate { state in
            state.pendingActions.removeAll { dueIDs.contains($0.id) }
        }
        for action in due {
            apply(action, stateStore: stateStore)
            TamperReporter.report(
                type: "pending_action_applied",
                details: action.describedFully
            )
        }
        return due
    }

    private static func reapplyDNSIfEnforcing(stateStore: StateStore) {
        let state = stateStore.snapshot()
        guard state.dnsEnforcementEnabled else { return }
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
    }
}
