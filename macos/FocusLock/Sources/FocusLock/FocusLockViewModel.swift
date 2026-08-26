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

    // MARK: Sudo broker terminal (see SudoBroker.swift) -- inert until the account is converted to
    // Standard, but the UI works the same either way since it's just a front-end for the XPC call.
    @Published var terminalCommandText: String = ""
    @Published var terminalReasonText: String = ""
    @Published var terminalLog: [TerminalEntry] = []
    @Published var terminalRunning = false
    // The command currently in flight (denylist -> allowlist -> possibly an AI-review round-trip)
    // -- shown as its own "reviewing..." row in the log immediately on submit, since the AI-review
    // tier alone can take several seconds and, with nothing shown until it resolves, looked
    // indistinguishable from the app being broken. `terminalRunning` alone only disabled the Run
    // button, with no other visible change while a command was in flight.
    @Published var pendingTerminalCommand: String?

    // MARK: AI Assistant chat box (see AIAssistantClient.swift) -- translates natural language into
    // command(s), each of which still goes through the SAME broker as the terminal above.
    @Published var assistantRequestText: String = ""
    @Published var assistantLog: [AssistantEntry] = []
    @Published var assistantRunning = false
    // Same "show it's actually working" fix as pendingTerminalCommand above.
    @Published var pendingAssistantRequest: String?

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

    func removeDomain(_ domain: String, passcode: String) {
        Task { await handleGated(await client.removeBlockedDomain(domain, passcode: passcode)) }
    }

    func addApp(_ app: BlockedApp) {
        Task { await handle(await client.addBlockedApp(app)) }
    }

    func removeApp(_ executableName: String, passcode: String) {
        Task { await handleGated(await client.removeBlockedApp(executableName: executableName, passcode: passcode)) }
    }

    func addProtectedApp(_ app: ProtectedApp) {
        Task { await handle(await client.addProtectedApp(app)) }
    }

    func removeProtectedApp(_ executableName: String, passcode: String) {
        Task { await handleGated(await client.removeProtectedApp(executableName: executableName, passcode: passcode)) }
    }

    func enableDNSEnforcement() {
        Task { await handle(await client.enableDNSEnforcement()) }
    }

    func disableDNSEnforcement(passcode: String) {
        Task { await handleGated(await client.disableDNSEnforcement(passcode: passcode)) }
    }

    func saveCloudFilterHost(passcode: String) {
        let host = cloudFilterHostText.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else { return }
        Task { await handleGated(await client.setCloudFilterHost(host, passcode: passcode)) }
    }

    /// Turning it on is ungated (no `passcode` needed); only the off direction is, since the GUI
    /// (see ContentView's passcode-prompt alert) only ever supplies one when disabling.
    func setCloudFilterEnabled(_ enabled: Bool, passcode: String = "") {
        Task { await handleGated(await client.setCloudFilterEnabled(enabled, passcode: passcode)) }
    }

    func runTerminalCommand() {
        let command = terminalCommandText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !command.isEmpty, !terminalRunning else { return }
        let reason = terminalReasonText
        terminalCommandText = ""
        terminalReasonText = ""
        terminalRunning = true
        pendingTerminalCommand = command
        Task {
            let result = await client.requestElevatedCommand(command: command, reason: reason)
            terminalLog.append(TerminalEntry(command: command, reason: reason, result: result))
            pendingTerminalCommand = nil
            terminalRunning = false
        }
    }

    func runAssistantRequest() {
        let request = assistantRequestText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !request.isEmpty, !assistantRunning else { return }
        assistantRequestText = ""
        assistantRunning = true
        pendingAssistantRequest = request
        Task {
            let result = await client.requestAssistantAction(request: request)
            assistantLog.append(AssistantEntry(request: request, result: result))
            pendingAssistantRequest = nil
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

    func installAvailableUpdate(passcode: String) {
        guard pendingUpdateManifest != nil else { return }
        updateInstalling = true
        updateStatusText = "Downloading and verifying..."
        Task {
            switch await client.installAvailableUpdate(passcode: passcode) {
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

    /// For passcode-gated calls. Surfaces the success message too (unlike `handle`), since a
    /// denial (wrong passcode, lockout) is the interesting case here. The passcode itself is
    /// supplied per-call by ContentView's passcode-prompt alert (see `pendingPasscodeAction`),
    /// not held in any published state here, so there's nothing to clear after use.
    private func handleGated(_ result: FocusLockResult) async {
        errorMessage = result.message
        await refreshOnce()
    }
}
