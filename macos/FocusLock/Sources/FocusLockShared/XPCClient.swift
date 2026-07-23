import Foundation

/// Thin async wrapper around the XPC connection to FocusLockHelperd. Shared by the GUI app and
/// the Guardian `focuslockctl` tool.
public final class FocusLockXPCClient {
    private var connection: NSXPCConnection?

    public init() {}

    // Plain `remoteObjectProxy` has no error handler: if the connection drops or is interrupted
    // before the daemon replies, the reply block we passed to the XPC call is simply never
    // invoked. Every call site below wraps its reply block in `withCheckedContinuation`, so a
    // proxy call that silently swallows its reply hangs that continuation -- and the awaiting
    // Task -- forever. `remoteObjectProxyWithErrorHandler` guarantees the error handler fires
    // in exactly that situation, so callers pass it through to resume with a failure instead.
    private func proxy(errorHandler: @escaping () -> Void) -> FocusLockXPCProtocol {
        if let connection, let proxy = connection.remoteObjectProxyWithErrorHandler({ _ in errorHandler() }) as? FocusLockXPCProtocol {
            return proxy
        }
        let newConnection = NSXPCConnection(machServiceName: FocusLockConstants.machServiceName, options: .privileged)
        newConnection.remoteObjectInterface = NSXPCInterface(with: FocusLockXPCProtocol.self)
        newConnection.invalidationHandler = { [weak self] in self?.connection = nil }
        newConnection.interruptionHandler = { [weak self] in self?.connection = nil }
        newConnection.resume()
        self.connection = newConnection
        guard let proxy = newConnection.remoteObjectProxyWithErrorHandler({ _ in errorHandler() }) as? FocusLockXPCProtocol else {
            fatalError("FocusLockHelperd XPC proxy unavailable -- is the daemon registered and running?")
        }
        return proxy
    }

    // Resumes a continuation at most once, whichever of the XPC reply or error handler fires
    // first -- guards against the (unlikely but possible) case where both fire.
    private func withXPCContinuation<T>(_ body: (@escaping (T) -> Void, @escaping () -> Void) -> Void, onError: T) async -> T {
        await withCheckedContinuation { continuation in
            var resumed = false
            let lock = NSLock()
            let resumeOnce: (T) -> Void = { value in
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                continuation.resume(returning: value)
            }
            body(resumeOnce, { resumeOnce(onError) })
        }
    }

    public func getStatus() async -> FocusLockState? {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).getStatus { data in
                resume(data.flatMap { FocusLockCodec.decode(FocusLockState.self, from: $0) })
            }
        }, onError: nil)
    }

    public func addBlockedApp(_ app: BlockedApp) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).addBlockedApp(FocusLockCodec.encode(app)) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func addBlockedDomain(_ domain: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).addBlockedDomain(domain) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func removeBlockedApp(executableName: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).removeBlockedApp(executableName: executableName) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func removeBlockedDomain(_ domain: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).removeBlockedDomain(domain) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func addProtectedApp(_ app: ProtectedApp) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).addProtectedApp(FocusLockCodec.encode(app)) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func removeProtectedApp(executableName: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).removeProtectedApp(executableName: executableName) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func enableDNSEnforcement() async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).enableDNSEnforcement { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func disableDNSEnforcement() async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).disableDNSEnforcement { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }
}
