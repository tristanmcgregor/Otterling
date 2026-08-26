import Foundation
import FocusLockShared

/// Every mutation `XPCService`'s local handlers and `DashboardConfigSync`'s remote reconcile can
/// make, applied immediately. Split out so both callers share one implementation of each action's
/// actual mutation rather than two copies that can drift. Nothing in this file is reachable
/// without having already cleared whatever gate is appropriate for the caller -- `XPCService`'s
/// passcode/admin-group check for a local caller, or possession of the dashboard's bearer token
/// for `DashboardConfigSync` -- see each of those files' doc comments.
enum ImmediateActionApplier {
    static func addBlockedApp(_ app: BlockedApp, stateStore: StateStore) {
        stateStore.mutate { state in
            if !state.blockedApps.contains(where: { $0.executableName == app.executableName }) {
                state.blockedApps.append(app)
            }
        }
    }

    static func removeBlockedApp(_ executableName: String, stateStore: StateStore) {
        stateStore.mutate { state in
            state.blockedApps.removeAll { $0.executableName == executableName }
        }
    }

    static func removeBlockedDomain(_ domain: String, stateStore: StateStore) {
        stateStore.mutate { state in
            state.blockedDomains.removeAll { $0 == domain }
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

    static func disableDNSEnforcement(stateStore: StateStore) {
        stateStore.mutate { state in
            state.dnsEnforcementEnabled = false
        }
        DNSEnforcer.remove()
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

    /// Reads the bundle path out of state before dropping the entry -- once it's gone there's
    /// nothing left to tell us which bundle to unlock, and it would stay `schg` forever.
    static func removeProtectedApp(_ executableName: String, stateStore: StateStore) {
        let bundlePath = stateStore.snapshot().protectedApps
            .first { $0.executableName == executableName }?.bundlePath
        if let bundlePath {
            AppProtector.unlock(bundlePath: bundlePath)
        }
        stateStore.mutate { state in
            state.protectedApps.removeAll { $0.executableName == executableName }
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

    static func disableProxyEnforcement(stateStore: StateStore) {
        stateStore.mutate { state in
            state.proxyEnforcementEnabled = false
            // The firewall force-through only makes sense while the proxy is enforced, so tearing
            // down the proxy takes it down too -- otherwise pf would keep dropping direct :443
            // with no proxy to route through, i.e. take web offline (the exact failure we forbid).
            state.forceProxyViaFirewall = false
        }
        ProxyEnforcer.remove()
    }

    static func setCloudFilterHost(_ host: String, stateStore: StateStore) {
        stateStore.mutate { state in
            state.cloudFilterHost = host
        }
        reapplyDNSIfEnforcing(stateStore: stateStore)
    }

    static func enableCloudFilter(stateStore: StateStore) {
        stateStore.mutate { state in
            state.cloudFilterEnabled = true
        }
        reapplyDNSIfEnforcing(stateStore: stateStore)
    }

    static func disableCloudFilter(stateStore: StateStore) {
        stateStore.mutate { state in
            state.cloudFilterEnabled = false
        }
        reapplyDNSIfEnforcing(stateStore: stateStore)
    }

    static func clearPasscode(stateStore: StateStore) {
        stateStore.mutate { state in
            state.guardianPasscode = nil
            state.passcodeConfigured = false
        }
    }

    private static func reapplyDNSIfEnforcing(stateStore: StateStore) {
        let state = stateStore.snapshot()
        guard state.dnsEnforcementEnabled else { return }
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
    }
}
