import Foundation
import FocusLockShared

/// Protection-INCREASING actions that apply immediately regardless of caller -- see
/// `XPCService`'s doc comment on the admin-group asymmetry: adding a block or enabling filtering
/// is always ungated, only removing/disabling is gated. Split out so `XPCService`'s local XPC
/// handlers and `DashboardConfigSync`'s remote reconcile share one implementation of each
/// action's actual mutation, rather than two copies that can drift.
enum ImmediateActionApplier {
    static func addBlockedApp(_ app: BlockedApp, stateStore: StateStore) {
        stateStore.mutate { state in
            if !state.blockedApps.contains(where: { $0.executableName == app.executableName }) {
                state.blockedApps.append(app)
            }
        }
    }

    static func enableDNSEnforcement(stateStore: StateStore) {
        stateStore.mutate { state in
            state.dnsEnforcementEnabled = true
        }
        // Apply immediately -- don't wait for the enforcement loop's own DNS cadence.
        let state = stateStore.snapshot()
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
    }

    /// Returns false (no-op) if no app bundle exists at `app.bundlePath` -- same guard
    /// `XPCService.addProtectedApp` applies before ever calling `AppProtector.lock`.
    @discardableResult
    static func addProtectedApp(_ app: ProtectedApp, stateStore: StateStore) -> Bool {
        guard FileManager.default.fileExists(atPath: app.bundlePath) else { return false }
        stateStore.mutate { state in
            if !state.protectedApps.contains(where: { $0.executableName == app.executableName }) {
                state.protectedApps.append(app)
            }
        }
        AppProtector.lock(bundlePath: app.bundlePath)
        return true
    }

    /// Only ever called with `hours >= ` the current value -- lowering is protection-reducing
    /// and goes through `PendingActionScheduler`'s `.lowerCooldownHours` instead. Matches
    /// `XPCService.setCooldownHours`'s own clamp.
    static func raiseCooldownHours(_ hours: Double, stateStore: StateStore) {
        let clamped = min(hours, FocusLockConstants.maximumCooldownHours)
        stateStore.mutate { state in
            state.cooldownHours = clamped
        }
    }

    static func enableProxyEnforcement(forceViaFirewall: Bool, stateStore: StateStore) {
        stateStore.mutate { state in
            state.proxyEnforcementEnabled = true
            state.forceProxyViaFirewall = forceViaFirewall
        }
        // Advisory -- ProxyEnforcer is fail-open (inert until reachable/provisioned); the
        // enforcement loop re-asserts this every tick regardless of this call's result.
        let state = stateStore.snapshot()
        ProxyEnforcer.apply(host: state.proxyHost, port: state.proxyPort, enabled: true)
    }

    static func enableCloudFilter(stateStore: StateStore) {
        stateStore.mutate { state in
            state.cloudFilterEnabled = true
        }
        reapplyDNSIfEnforcing(stateStore: stateStore)
    }

    private static func reapplyDNSIfEnforcing(stateStore: StateStore) {
        let state = stateStore.snapshot()
        guard state.dnsEnforcementEnabled else { return }
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
    }
}
