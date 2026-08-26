import Foundation
import FocusLockShared

/// Periodic, fully-automatic update check-and-install -- mirrors Android's `UpdateCheckWorker`
/// periodic path (same "the automatic and manual checks are the exact same code" reasoning), but
/// on its own timer rather than folded into `EnforcementLoop`: a successful install ends this
/// process (`UpdateManager.restartAfterInstall()`), which has no business happening mid-tick of the
/// enforcement loop's DNS/hosts/pf/app-block work.
///
/// Runs on its own background queue so `UpdateManager`'s blocking network calls never touch the
/// XPC listener's or the enforcement loop's queue.
final class UpdateCheckLoop {
    static let shared = UpdateCheckLoop()

    private var stateStore: StateStore?
    private var timer: Timer?
    private let queue = DispatchQueue(label: "app.otterling.updatecheck")

    func start(stateStore: StateStore, interval: TimeInterval = 60 * 60) {
        self.stateStore = stateStore
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            self?.checkNow()
        }
        RunLoop.current.add(timer, forMode: .common)
        self.timer = timer
    }

    private func checkNow() {
        queue.async { [weak self] in
            guard let self, let stateStore = self.stateStore else { return }
            let host = stateStore.snapshot().cloudFilterHost
            switch UpdateManager.checkForUpdate(host: host) {
            case .upToDate, .error:
                return
            case .updateAvailable(let manifest):
                FileHandle.standardError.write(
                    "[update] \(manifest.versionName) available, installing\n".data(using: .utf8)!
                )
                let result = UpdateManager.downloadVerifyAndInstall(manifest)
                switch result {
                case .installedPendingRestart:
                    FileHandle.standardError.write(
                        "[update] installed \(manifest.versionName), restarting\n".data(using: .utf8)!
                    )
                    // Persisted (survives the restart below) so the GUI can show a local "Otterling
                    // updated" notification -- see FocusLockState.lastAutoUpdateVersion's doc comment.
                    stateStore.mutate {
                        $0.lastAutoUpdateVersion = manifest.versionName
                        $0.lastAutoUpdateAt = Date()
                    }
                    UpdateManager.restartAfterInstall()
                case .rejected(let reason):
                    FileHandle.standardError.write("[update] install rejected: \(reason)\n".data(using: .utf8)!)
                }
            }
        }
    }
}
