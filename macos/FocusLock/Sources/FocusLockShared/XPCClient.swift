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

    public func removeBlockedApp(executableName: String, passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).removeBlockedApp(executableName: executableName, passcode: passcode) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func removeBlockedDomain(_ domain: String, passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).removeBlockedDomain(domain, passcode: passcode) { data in
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

    public func removeProtectedApp(executableName: String, passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).removeProtectedApp(executableName: executableName, passcode: passcode) { data in
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

    public func disableDNSEnforcement(passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).disableDNSEnforcement(passcode: passcode) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func enableProxyEnforcement(forceViaFirewall: Bool) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).enableProxyEnforcement(forceViaFirewall: forceViaFirewall) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func disableProxyEnforcement(passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).disableProxyEnforcement(passcode: passcode) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func setCloudFilterHost(_ host: String, passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).setCloudFilterHost(host, passcode: passcode) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func setCloudFilterEnabled(_ enabled: Bool, passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).setCloudFilterEnabled(enabled, passcode: passcode) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func setGuardianPasscode(newPasscode: String, currentPasscode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).setGuardianPasscode(newPasscode: newPasscode, currentPasscode: currentPasscode) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func setCooldownHours(_ hours: Double, passcode: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).setCooldownHours(hours, passcode: passcode) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func cancelPendingAction(id: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).cancelPendingAction(id: id) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func checkForUpdate() async -> UpdateCheckStatus? {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).checkForUpdate { data in
                resume(FocusLockCodec.decode(UpdateCheckStatus.self, from: data))
            }
        }, onError: nil)
    }

    public func installAvailableUpdate(passcode: String) async -> UpdateInstallResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).installAvailableUpdate(passcode: passcode) { data in
                resume(FocusLockCodec.decode(UpdateInstallResult.self, from: data) ?? .rejected("Malformed reply"))
            }
        }, onError: .rejected("Could not reach FocusLockHelperd"))
    }

    public func protectUser(username: String) async -> FocusLockResult {
        await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).protectUser(username: username) { data in
                resume(FocusLockCodec.decode(FocusLockResult.self, from: data) ?? .denied("Malformed reply"))
            }
        }, onError: .denied("Could not reach FocusLockHelperd"))
    }

    public func requestElevatedCommand(command: String, reason: String) async -> ElevatedCommandResult {
        let request = FocusLockCodec.encode(ElevatedCommandRequest(command: command, reason: reason))
        return await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).requestElevatedCommand(request) { data in
                resume(
                    FocusLockCodec.decode(ElevatedCommandResult.self, from: data)
                        ?? ElevatedCommandResult(approved: false, source: "error", explanation: "Malformed reply")
                )
            }
        }, onError: ElevatedCommandResult(approved: false, source: "error", explanation: "Could not reach FocusLockHelperd"))
    }

    public func requestAssistantAction(request: String) async -> AssistantActionResult {
        let payload = FocusLockCodec.encode(AssistantRequest(request: request))
        return await withXPCContinuation({ resume, onError in
            proxy(errorHandler: onError).requestAssistantAction(payload) { data in
                resume(
                    FocusLockCodec.decode(AssistantActionResult.self, from: data)
                        ?? AssistantActionResult(translationExplanation: "Malformed reply", steps: [])
                )
            }
        }, onError: AssistantActionResult(translationExplanation: "Could not reach FocusLockHelperd", steps: []))
    }
}
