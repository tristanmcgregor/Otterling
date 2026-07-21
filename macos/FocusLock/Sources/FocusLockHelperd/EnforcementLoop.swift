import Foundation
import FocusLockShared

/// Periodically re-asserts the current block state: kills matching processes and (re)applies
/// site/content blocking. Runs independently of XPC calls so a reboot or a killed GUI app
/// doesn't lift anything -- this loop reads persisted state straight off disk via `stateStore`.
///
/// Process-kill and site-blocking logic land in later steps; this stub just proves the timer
/// wiring and logging work end-to-end.
final class EnforcementLoop {
    static let shared = EnforcementLoop()

    private var stateStore: StateStore?
    private var timer: Timer?
    private let queue = DispatchQueue(label: "au.com.tbmcgregor.bwparker.focuslock.enforcement")

    func start(stateStore: StateStore, interval: TimeInterval = 3) {
        self.stateStore = stateStore
        reapplyNow()
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            self?.reapplyNow()
        }
        RunLoop.current.add(timer, forMode: .common)
        self.timer = timer
    }

    func reapplyNow() {
        queue.async { [weak self] in
            guard let self, let stateStore = self.stateStore else { return }
            let state = stateStore.snapshot()
            FileHandle.standardError.write(
                "[enforcement] active=\(state.isSessionActive) apps=\(state.blockedApps.count) domains=\(state.blockedDomains.count) remaining=\(Int(state.remainingSeconds))s\n"
                    .data(using: .utf8)!
            )
        }
    }
}
