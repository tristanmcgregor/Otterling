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

    func removeDomain(_ domain: String) {
        Task { await handle(await client.removeBlockedDomain(domain)) }
    }

    func addApp(_ app: BlockedApp) {
        Task { await handle(await client.addBlockedApp(app)) }
    }

    func removeApp(_ executableName: String) {
        Task { await handle(await client.removeBlockedApp(executableName: executableName)) }
    }

    func addProtectedApp(_ app: ProtectedApp) {
        Task { await handle(await client.addProtectedApp(app)) }
    }

    func removeProtectedApp(_ executableName: String) {
        Task { await handle(await client.removeProtectedApp(executableName: executableName)) }
    }

    func enableDNSEnforcement() {
        Task { await handle(await client.enableDNSEnforcement()) }
    }

    func disableDNSEnforcement() {
        Task { await handle(await client.disableDNSEnforcement()) }
    }

    func saveCloudFilterHost() {
        let host = cloudFilterHostText.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty else { return }
        Task { await handle(await client.setCloudFilterHost(host)) }
    }

    func setCloudFilterEnabled(_ enabled: Bool) {
        Task { await handle(await client.setCloudFilterEnabled(enabled)) }
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
            switch await client.installAvailableUpdate() {
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
}
