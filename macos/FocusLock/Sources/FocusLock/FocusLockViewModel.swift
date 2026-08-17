import Foundation
import FocusLockShared

/// Bridges the GUI to the daemon over XPC. Polls status on a timer rather than pushing from the
/// daemon so the daemon's XPC surface stays one-directional and simple; a 1s poll is cheap and
/// keeps the countdown feeling live.
@MainActor
final class FocusLockViewModel: ObservableObject {
    @Published var state = FocusLockState()
    @Published var errorMessage: String?
    @Published var newDomainText: String = ""
    @Published var cloudFilterHostText: String = ""
    @Published var cloudFilterTestResult: String?
    @Published var cloudFilterTesting = false
    @Published var updateStatusText: String = ""
    @Published var updateChecking = false
    @Published var updateInstalling = false
    @Published var updateAvailable = false
    /// Bound to the passcode field. Cleared after every gated call so it isn't left sitting in
    /// memory (or on screen) once it's been used.
    @Published var passcodeText: String = ""
    @Published var newPasscodeText: String = ""
    @Published var confirmPasscodeText: String = ""
    @Published var passcodeStatusText: String?
    @Published var cooldownHoursText: String = ""

    // MARK: Sudo broker terminal (see SudoBroker.swift) -- inert until the account is converted to
    // Standard, but the UI works the same either way since it's just a front-end for the XPC call.
    @Published var terminalCommandText: String = ""
    @Published var terminalReasonText: String = ""
    @Published var terminalLog: [TerminalEntry] = []
    @Published var terminalRunning = false

    // MARK: AI Assistant chat box (see AIAssistantClient.swift) -- translates natural language into
    // command(s), each of which still goes through the SAME broker as the terminal above.
    @Published var assistantRequestText: String = ""
    @Published var assistantLog: [AssistantEntry] = []
    @Published var assistantRunning = false

    // MARK: Protect Another User (see UserScannerInstaller.swift)
    @Published var protectUsernameText: String = ""
    @Published var protectUserStatusText: String?

    struct TerminalEntry: Identifiable {
        let id = UUID()
        let command: String
        let reason: String
        let result: ElevatedCommandResult
    }

    struct AssistantEntry: Identifiable {
        let id = UUID()
        let request: String
        let result: AssistantActionResult
    }

    private var pendingUpdateManifest: UpdateManifest?
    private var didSeedCooldownText = false

    private let client = FocusLockXPCClient()
    private var pollTask: Task<Void, Never>?
    // Seeds cloudFilterHostText from server state exactly once (on first load), then never again
    // -- otherwise the 1s poll would clobber an in-progress edit before the user taps Save.
    private var didSeedHostText = false

    func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task {
            while !Task.isCancelled {
                await refreshOnce()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    func refreshOnce() async {
        if let status = await client.getStatus() {
            state = status
            if !didSeedHostText {
                cloudFilterHostText = status.cloudFilterHost
                didSeedHostText = true
            }
            // Same one-shot seeding as the host field, and for the same reason: the 1s poll would
            // otherwise overwrite an in-progress edit.
            if !didSeedCooldownText {
                cooldownHoursText = String(Int(status.cooldownHours))
                didSeedCooldownText = true
            }
        }
    }

    func addDomain() {
        let domain = newDomainText.trimmingCharacters(in: .whitespaces)
        guard !domain.isEmpty else { return }
        Task {
            let result = await client.addBlockedDomain(domain)
            if result.success { newDomainText = "" }
            await handle(result)
        }
    }

    func removeDomain(_ domain: String) {
        Task { await handleGated(await client.removeBlockedDomain(domain, passcode: passcodeText)) }
    }

    func addApp(_ app: BlockedApp) {
        Task { await handle(await client.addBlockedApp(app)) }
    }

    func removeApp(_ executableName: String) {
        Task { await handleGated(await client.removeBlockedApp(executableName: executableName, passcode: passcodeText)) }
    }

    func addProtectedApp(_ app: ProtectedApp) {
        Task { await handle(await client.addProtectedApp(app)) }
    }

    func removeProtectedApp(_ executableName: String) {
        Task { await handleGated(await client.removeProtectedApp(executableName: executableName, passcode: passcodeText)) }
    }

    func enableDNSEnforcement() {
        Task { await handle(await client.enableDNSEnforcement()) }
    }

    func disableDNSEnforcement() {
        Task { await handleGated(await client.disableDNSEnforcement(passcode: passcodeText)) }
    }

    func saveCloudFilterHost() {
        let host = cloudFilterHostText.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else { return }
        Task { await handleGated(await client.setCloudFilterHost(host, passcode: passcodeText)) }
    }

    func setCloudFilterEnabled(_ enabled: Bool) {
        // Turning it on is ungated; only the off direction needs the passcode.
        Task { await handleGated(await client.setCloudFilterEnabled(enabled, passcode: passcodeText)) }
    }

    func cancelPendingAction(_ id: String) {
        Task { await handle(await client.cancelPendingAction(id: id)) }
    }

    func runTerminalCommand() {
        let command = terminalCommandText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !command.isEmpty, !terminalRunning else { return }
        let reason = terminalReasonText
        terminalCommandText = ""
        terminalReasonText = ""
        terminalRunning = true
        Task {
            let result = await client.requestElevatedCommand(command: command, reason: reason)
            terminalLog.append(TerminalEntry(command: command, reason: reason, result: result))
            terminalRunning = false
        }
    }

    func runAssistantRequest() {
        let request = assistantRequestText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !request.isEmpty, !assistantRunning else { return }
        assistantRequestText = ""
        assistantRunning = true
        Task {
            let result = await client.requestAssistantAction(request: request)
            assistantLog.append(AssistantEntry(request: request, result: result))
            assistantRunning = false
        }
    }

    func protectUser(_ username: String) {
        Task {
            let result = await client.protectUser(username: username)
            protectUserStatusText = result.message ?? (result.success ? "Done." : "Failed.")
            if result.success { protectUsernameText = "" }
        }
    }

    func setGuardianPasscode() {
        let new = newPasscodeText
        guard !new.isEmpty else {
            passcodeStatusText = "Enter a new passcode."
            return
        }
        guard new == confirmPasscodeText else {
            passcodeStatusText = "Passcodes don't match."
            return
        }
        Task {
            let result = await client.setGuardianPasscode(newPasscode: new, currentPasscode: passcodeText)
            passcodeStatusText = result.message ?? (result.success ? "Passcode updated." : "Denied.")
            if result.success {
                newPasscodeText = ""
                confirmPasscodeText = ""
                passcodeText = ""
            }
            await refreshOnce()
        }
    }

    func saveCooldownHours() {
        guard let hours = Double(cooldownHoursText.trimmingCharacters(in: .whitespaces)) else {
            errorMessage = "Cooldown must be a number of hours."
            return
        }
        Task { await handleGated(await client.setCooldownHours(hours, passcode: passcodeText)) }
    }

    func testCloudFilterReachability() {
        let host = cloudFilterHostText.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else {
            cloudFilterTestResult = "Enter a host first."
            return
        }
        cloudFilterTesting = true
        cloudFilterTestResult = nil
        Task {
            let reachable = await CloudFilterProbe.testReachable(host: host)
            cloudFilterTestResult = reachable ? "Filter server reachable." : "Filter server unreachable."
            cloudFilterTesting = false
        }
    }

    func checkForUpdate() {
        updateChecking = true
        updateStatusText = "Checking..."
        updateAvailable = false
        pendingUpdateManifest = nil
        Task {
            switch await client.checkForUpdate() {
            case .upToDate:
                updateStatusText = "Up to date (build \(FocusLockConstants.appVersionCode))."
            case .updateAvailable(let manifest):
                pendingUpdateManifest = manifest
                updateAvailable = true
                updateStatusText = "Update available: \(manifest.versionName)."
            case .error(let message):
                updateStatusText = "Check failed: \(message)"
            case nil:
                updateStatusText = "Could not reach FocusLockHelperd."
            }
            updateChecking = false
        }
    }

    func installAvailableUpdate() {
        guard pendingUpdateManifest != nil else { return }
        updateInstalling = true
        updateStatusText = "Downloading and verifying..."
        Task {
            switch await client.installAvailableUpdate(passcode: passcodeText) {
            case .installedPendingRestart(let manifest):
                updateStatusText = "Installed \(manifest.versionName) -- restarting the filter daemon now."
                updateAvailable = false
            case .rejected(let reason):
                updateStatusText = "Install failed: \(reason)"
                updateInstalling = false
            }
        }
    }

    private func handle(_ result: FocusLockResult) async {
        if !result.success {
            errorMessage = result.message ?? "Action denied."
        }
        await refreshOnce()
    }

    /// For passcode-gated calls. Surfaces the success message too (unlike `handle`), because a
    /// scheduled action's reply is where the user finds out *when* it lands and how to cancel it --
    /// silently succeeding would make a 24h-delayed change look like a no-op.
    private func handleGated(_ result: FocusLockResult) async {
        errorMessage = result.message
        // Never leave the passcode sitting in a bound field after it's been spent.
        passcodeText = ""
        await refreshOnce()
    }
}
