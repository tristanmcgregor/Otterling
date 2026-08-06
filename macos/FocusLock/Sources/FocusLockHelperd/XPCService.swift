import Foundation
import FocusLockShared

/// Implements the daemon side of FocusLockXPCProtocol. This is where the Guardian-account
/// asymmetry is actually enforced: `removeBlockedApp`/`removeBlockedDomain` check the *real* uid
/// of the connecting process and reject the call outright unless that uid is in the `admin`
/// group. The GUI binary has no way to make this succeed from a Standard account -- the check
/// happens here, not in the UI.
final class XPCService: NSObject, FocusLockXPCProtocol {
    private let stateStore: StateStore
    private let onStateChanged: () -> Void

    init(stateStore: StateStore, onStateChanged: @escaping () -> Void) {
        self.stateStore = stateStore
        self.onStateChanged = onStateChanged
    }

    private func isCallerAdmin() -> Bool {
        guard let connection = NSXPCConnection.current() else { return false }
        return AdminGroupCheck.isUserAdmin(uid: uid_t(connection.effectiveUserIdentifier))
    }

    func getStatus(reply: @escaping (Data?) -> Void) {
        reply(FocusLockCodec.encode(stateStore.snapshot()))
    }

    func addBlockedApp(_ appJSON: Data, reply: @escaping (Data) -> Void) {
        guard let app = FocusLockCodec.decode(BlockedApp.self, from: appJSON) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid app payload")))
            return
        }
        stateStore.mutate { state in
            if !state.blockedApps.contains(where: { $0.executableName == app.executableName }) {
                state.blockedApps.append(app)
            }
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func addBlockedDomain(_ domain: String, reply: @escaping (Data) -> Void) {
        let normalized = domain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalized.isEmpty else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Empty domain")))
            return
        }
        stateStore.mutate { state in
            if !state.blockedDomains.contains(normalized) {
                state.blockedDomains.append(normalized)
            }
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func removeBlockedApp(executableName: String, reply: @escaping (Data) -> Void) {
        guard isCallerAdmin() else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Only the Guardian admin account can remove a blocked app.")))
            return
        }
        stateStore.mutate { state in
            state.blockedApps.removeAll { $0.executableName == executableName }
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func removeBlockedDomain(_ domain: String, reply: @escaping (Data) -> Void) {
        guard isCallerAdmin() else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Only the Guardian admin account can remove a blocked domain.")))
            return
        }
        let normalized = domain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        stateStore.mutate { state in
            state.blockedDomains.removeAll { $0 == normalized }
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func addProtectedApp(_ appJSON: Data, reply: @escaping (Data) -> Void) {
        guard let app = FocusLockCodec.decode(ProtectedApp.self, from: appJSON) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Invalid app payload")))
            return
        }
        guard FileManager.default.fileExists(atPath: app.bundlePath) else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("No app bundle found at \(app.bundlePath)")))
            return
        }
        stateStore.mutate { state in
            if !state.protectedApps.contains(where: { $0.executableName == app.executableName }) {
                state.protectedApps.append(app)
            }
        }
        AppProtector.lock(bundlePath: app.bundlePath)
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func removeProtectedApp(executableName: String, reply: @escaping (Data) -> Void) {
        guard isCallerAdmin() else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Only the Guardian admin account can remove a protected app.")))
            return
        }
        stateStore.mutate { state in
            if let app = state.protectedApps.first(where: { $0.executableName == executableName }) {
                AppProtector.unlock(bundlePath: app.bundlePath)
            }
            state.protectedApps.removeAll { $0.executableName == executableName }
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func enableDNSEnforcement(reply: @escaping (Data) -> Void) {
        stateStore.mutate { state in
            state.dnsEnforcementEnabled = true
        }
        // Apply immediately -- don't wait for the enforcement loop's 15s DNS cadence.
        let state = stateStore.snapshot()
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func disableDNSEnforcement(reply: @escaping (Data) -> Void) {
        guard isCallerAdmin() else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Only the Guardian admin account can disable DNS enforcement.")))
            return
        }
        stateStore.mutate { state in
            state.dnsEnforcementEnabled = false
        }
        DNSEnforcer.remove()
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func setCloudFilterHost(_ host: String, reply: @escaping (Data) -> Void) {
        let normalized = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Empty host")))
            return
        }
        stateStore.mutate { state in
            state.cloudFilterHost = normalized
        }
        reapplyDNSIfEnforcing()
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    func setCloudFilterEnabled(_ enabled: Bool, reply: @escaping (Data) -> Void) {
        if !enabled && !isCallerAdmin() {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Only the Guardian admin account can turn off the cloud filter.")))
            return
        }
        stateStore.mutate { state in
            state.cloudFilterEnabled = enabled
        }
        reapplyDNSIfEnforcing()
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }

    /// Re-resolves and re-asserts DNS immediately (rather than waiting for the enforcement loop's
    /// 15s cadence) whenever the cloud filter host/toggle changes while DNS enforcement is
    /// already on -- otherwise a host edit wouldn't visibly take effect for up to 15s.
    private func reapplyDNSIfEnforcing() {
        let state = stateStore.snapshot()
        guard state.dnsEnforcementEnabled else { return }
        DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
    }
}
