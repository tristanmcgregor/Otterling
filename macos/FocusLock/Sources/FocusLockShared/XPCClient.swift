import Foundation

/// Thin async wrapper around the XPC connection to FocusLockHelperd. Shared by the GUI app and
/// the Guardian `focuslockctl` tool.
public final class FocusLockXPCClient {
    private var connection: NSXPCConnection?

    public init() {}

    private func proxy() -> FocusLockXPCProtocol {
        if let connection, let proxy = connection.remoteObjectProxy as? FocusLockXPCProtocol {
            return proxy
        }
        let newConnection = NSXPCConnection(machServiceName: FocusLockConstants.machServiceName, options: .privileged)
        newConnection.remoteObjectInterface = NSXPCInterface(with: FocusLockXPCProtocol.self)
        newConnection.invalidationHandler = { [weak self] in self?.connection = nil }
        newConnection.interruptionHandler = { [weak self] in self?.connection = nil }
        newConnection.resume()
        self.connection = newConnection
        guard let proxy = newConnection.remoteObjectProxy as? FocusLockXPCProtocol else {
            fatalError("FocusLockHelperd XPC proxy unavailable -- is the daemon registered and running?")
        }
        return proxy
    }

    public func getStatus() async -> FocusLockState? {
        await withCheckedContinuation { continuation in
            proxy().getStatus { data in
                continuation.resume(returning: data.flatMap { FocusLockCodec.decode(FocusLockState.self, from: $0) })
            }
        }
    }

    public func addBlockedApp(_ app: BlockedApp) async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().addBlockedApp(FocusLockCodec.encode(app)) { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func addBlockedDomain(_ domain: String) async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().addBlockedDomain(domain) { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func removeBlockedApp(executableName: String) async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().removeBlockedApp(executableName: executableName) { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func removeBlockedDomain(_ domain: String) async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().removeBlockedDomain(domain) { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func addProtectedApp(_ app: ProtectedApp) async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().addProtectedApp(FocusLockCodec.encode(app)) { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func removeProtectedApp(executableName: String) async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().removeProtectedApp(executableName: executableName) { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func enableDNSEnforcement() async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().enableDNSEnforcement { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func disableDNSEnforcement() async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().disableDNSEnforcement { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }

    public func getGuardianSetupPublicKey() async -> String? {
        await withCheckedContinuation { continuation in
            proxy().getGuardianSetupPublicKey { key in
                continuation.resume(returning: key)
            }
        }
    }

    public func applyGuardianSetupCiphertext(_ base64Ciphertext: String) async -> FocusLockResult {
        await withCheckedContinuation { continuation in
            proxy().applyGuardianSetupCiphertext(base64Ciphertext) { data in
                continuation.resume(returning: FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }
    }
}
