import AppKit
import Foundation
import FocusLockShared
import UserNotifications

/// Bridges the GUI to the daemon over XPC. Polls status on a timer rather than pushing from the
/// daemon so the daemon's XPC surface stays one-directional and simple; a 1s poll is cheap and
/// keeps the countdown feeling live.
@MainActor
final class FocusLockViewModel: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
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

    // MARK: AI Assistant chat box (see AIAssistantClient.swift) -- works each request as a
    // multi-round agent loop server-side, but every command it proposes in every round still goes
    // through the SAME broker as the terminal above.
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

    // Local (not accountability-reporting) notification for a completed background update -- see
    // FocusLockState.lastAutoUpdateVersion's doc comment for why the daemon persists this instead
    // of pushing it to the GUI directly. Key is scoped to this app (UserDefaults.standard is
    // per-bundle-identifier) so it survives the GUI being quit/relaunched, not just this process.
    private static let notifiedUpdateVersionKey = "otterling.lastNotifiedAutoUpdateVersion"

    func startPolling() {
        guard pollTask == nil else { return }
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
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
            notifyIfNewAutoUpdate(status)
        }
    }

    /// Fires a local banner the first time this GUI process notices a NEW `lastAutoUpdateVersion`
    /// -- not on every 1s poll, and not re-fired for a version already notified about (including
    /// across a GUI relaunch, via UserDefaults). This is a local-only heads-up, deliberately not
    /// the accountability-reporting path (`TamperReporter`/ntfy) -- a background update installing
    /// isn't a tamper/protection-reducing event, just something worth a quiet "hey, this happened."
    private func notifyIfNewAutoUpdate(_ status: FocusLockState) {
        guard let version = status.lastAutoUpdateVersion, !version.isEmpty else { return }
        let defaults = UserDefaults.standard
        guard defaults.string(forKey: Self.notifiedUpdateVersionKey) != version else { return }
        defaults.set(version, forKey: Self.notifiedUpdateVersionKey)

        let content = UNMutableNotificationContent()
        content.title = "Otterling updated"
        content.body = "Now running v\(version)."
        content.sound = .default
        let request = UNNotificationRequest(identifier: "app-updated-\(version)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Shows the banner even if this GUI window happens to be frontmost when the notification
    /// fires -- without this, UNUserNotificationCenter suppresses it while the app is active.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
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

    /// The build actually protecting this Mac right now -- see `FocusLockState.daemonVersionCode`'s
    /// doc comment for why this, not `FocusLockConstants.appVersionCode` (this GUI process's own
    /// compiled-in value), is the only number safe to show anywhere as "the current build." `0` is
    /// its "not yet known" sentinel -- either no `getStatus` reply has landed yet (a moment after
    /// launch), or it landed from a daemon still running a build that predates this field entirely
    /// (i.e. the daemon side of an update hasn't actually restarted yet even though this GUI has) --
    /// never a real build number, so it's never shown as one.
    var currentBuildLabel: String {
        state.daemonVersionCode > 0 ? "\(state.daemonVersionCode)" : "…"
    }

    func checkForUpdate() {
        updateChecking = true
        updateStatusText = "Checking..."
        updateAvailable = false
        pendingUpdateManifest = nil
        Task {
            switch await client.checkForUpdate() {
            case .upToDate:
                // The daemon that just answered this check is the authority on its own version --
                // not this GUI process's own compiled-in appVersionCode, which can genuinely differ
                // if the two haven't restarted in lockstep. See FocusLockState.daemonVersionCode.
                updateStatusText = "Up to date (build \(currentBuildLabel))."
            case .updateAvailable(let manifest):
                pendingUpdateManifest = manifest
                updateAvailable = true
                // Names both sides of the comparison -- a guardian seeing this while the "Build N"
                // pill nearby shows a build number that looks current is exactly the confusing case
                // FocusLockState.daemonVersionCode's doc comment describes; spelling out what this
                // is actually being compared against makes a GUI/daemon version mismatch visible
                // instead of just looking wrong.
                updateStatusText = "Update available: \(manifest.versionName) (currently running build \(currentBuildLabel))."
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
            switch await client.installAvailableUpdate() {
            case .installedPendingRestart(let manifest):
                updateStatusText = "Installed \(manifest.versionName) -- restarting now."
                updateAvailable = false
                relaunchAfterUpdate()
            case .rejected(let reason):
                updateStatusText = "Install failed: \(reason)"
                updateInstalling = false
            }
        }
    }

    /// The daemon already restarts itself (`UpdateManager.restartAfterInstall`, ~2s after replying
    /// over XPC so this call actually gets its response first) -- this is the GUI-side half of the
    /// same "restart everything" story. A running foreground app can't replace its own in-memory
    /// binary, so the only way to actually pick up the freshly-installed GUI executable is to spawn
    /// a brand-new instance of it (from the now-updated `/Applications/Otterling.app`) and quit this
    /// one. The delay gives the daemon time to actually finish swapping/restarting first, so the new
    /// GUI instance's first status poll doesn't race a daemon that's still mid-restart.
    private func relaunchAfterUpdate() {
        let appURL = Bundle.main.bundleURL
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            let config = NSWorkspace.OpenConfiguration()
            config.createsNewApplicationInstance = true
            NSWorkspace.shared.openApplication(at: appURL, configuration: config) { _, error in
                DispatchQueue.main.async {
                    if let error {
                        // Don't strand the user on a stale binary with no way back in -- surface it
                        // and let them reopen Otterling.app by hand instead of silently doing nothing.
                        self.updateStatusText = "Installed, but couldn't relaunch automatically (\(error.localizedDescription)) -- reopen Otterling.app manually."
                        self.updateInstalling = false
                        return
                    }
                    NSApplication.shared.terminate(nil)
                }
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
