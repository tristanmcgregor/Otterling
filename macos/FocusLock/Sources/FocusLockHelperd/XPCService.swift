import Foundation
import FocusLockShared

/// Implements the daemon side of FocusLockXPCProtocol. This is where the Guardian-account
/// asymmetry is actually enforced: `removeBlockedApp`/`removeBlockedDomain`/`endSessionEarly`
/// check the *real* uid of the connecting process and reject the call outright unless that uid
/// is in the `admin` group. The GUI binary has no way to make this succeed from a Standard
/// account -- the check happens here, not in the UI.
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

    func startOrExtendSession(durationSeconds: Double, reply: @escaping (Data) -> Void) {
        guard durationSeconds > 0 else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Duration must be positive")))
            return
        }
        stateStore.mutate { state in
            let candidate = Date().addingTimeInterval(durationSeconds)
            // Only ever allowed to move the expiry later, never earlier -- "start" on top of an
            // active session behaves as "extend at least to now + duration".
            if let existing = state.sessionExpiresAt, existing > candidate {
                return
            }
            state.sessionExpiresAt = candidate
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

    func endSessionEarly(reply: @escaping (Data) -> Void) {
        guard isCallerAdmin() else {
            reply(FocusLockCodec.encode(FocusLockResult.denied("Only the Guardian admin account can end a session early.")))
            return
        }
        stateStore.mutate { state in
            state.sessionExpiresAt = nil
        }
        onStateChanged()
        reply(FocusLockCodec.encode(FocusLockResult.ok))
    }
}
