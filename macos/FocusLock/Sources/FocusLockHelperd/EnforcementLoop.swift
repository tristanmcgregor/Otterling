import Foundation
import FocusLockShared

/// Periodically re-asserts the current block state: kills matching processes and (re)applies
/// site/content blocking. Runs independently of XPC calls so a reboot or a killed GUI app
/// doesn't lift anything -- this loop reads persisted state straight off disk via `stateStore`.
///
/// Blocking is unconditional: anything in blockedApps/blockedDomains is enforced 24/7 as soon as
/// it's added, with no session/timer to wait out. It only stops once the Guardian removes it.
final class EnforcementLoop {
    static let shared = EnforcementLoop()

    private var stateStore: StateStore?
    private var timer: Timer?
    private let queue = DispatchQueue(label: "au.com.tbmcgregor.bwparker.focuslock.enforcement")

    // Tracks what's currently applied to /etc/hosts and pf so reapplyNow() (called on every XPC
    // mutation plus every timer tick) only touches the filesystem/pf when something changed.
    private var lastAppliedDomains: [String] = []
    private var lastAppliedSiteBlockActive = false

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

            let domainsToBlock = state.blockedDomains
            if domainsToBlock != self.lastAppliedDomains {
                HostsFileBlocker.apply(domains: domainsToBlock)
                self.lastAppliedDomains = domainsToBlock
            }

            let siteBlockActive = !domainsToBlock.isEmpty
            if siteBlockActive != self.lastAppliedSiteBlockActive {
                PFBlocker.apply(active: siteBlockActive)
                self.lastAppliedSiteBlockActive = siteBlockActive
            }

            let killed = AppBlockEnforcer.enforce(blockedApps: state.blockedApps)
            if !killed.isEmpty {
                FileHandle.standardError.write(
                    "[enforcement] killed: \(killed.joined(separator: ", "))\n".data(using: .utf8)!
                )
            }
        }
    }
}
