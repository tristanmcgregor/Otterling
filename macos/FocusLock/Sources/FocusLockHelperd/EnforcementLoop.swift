import Foundation
import FocusLockShared

/// Periodically re-asserts the current block state: kills matching processes, (re)applies
/// site/content blocking (Guardian's manual list plus the always-on downloaded adult-domain hosts
/// list from `AdultBlocklistManager`), keeps protected apps alive and locked, and (re)points DNS
/// at the cloud content filter (or Cloudflare Family as fallback) when enabled. Runs independently
/// of XPC calls so a reboot or a killed GUI app doesn't lift anything -- this loop reads persisted
/// state straight off disk via `stateStore`.
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
    private var lastAppliedPFActive = false
    private var lastAppliedAllowedResolverIPs: [String] = []

    // Debounces relaunch attempts per app so a slow-starting process (which won't show up in a
    // process scan for a second or two) doesn't get `open`'d again on every tick before it's had
    // a chance to appear. Backs off exponentially (capped) on consecutive failures -- e.g. no
    // console session (locked/logged-out) -- so a permanently-failing relaunch doesn't flood
    // RunningBoard with launch requests forever; that was observed to degrade unrelated system
    // services (sfltool/spctl calls taking 40s+ under that load).
    private var lastRelaunchAttempt: [String: Date] = [:]
    private var consecutiveFailures: [String: Int] = [:]
    private let baseRelaunchCooldown: TimeInterval = 6
    private let maxRelaunchCooldown: TimeInterval = 300

    private func cooldown(forConsecutiveFailures failures: Int) -> TimeInterval {
        guard failures > 0 else { return baseRelaunchCooldown }
        return min(baseRelaunchCooldown * pow(2, Double(failures)), maxRelaunchCooldown)
    }

    // DNSEnforcer shells out to networksetup a few times per network service; that's cheap
    // enough occasionally but wasteful on every 3s tick, so it's checked on its own slower
    // cadence instead.
    private var lastDNSCheckAt: Date?
    private let dnsCheckInterval: TimeInterval = 15

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

            // Cheap staleness check -- only actually dispatches a download (on its own background
            // queue) if the cache is missing or a day old, so this never stalls this tick.
            AdultBlocklistManager.shared.refreshIfStale()
            // Always merged in, independent of dnsEnforcementEnabled: this is the local, always-on
            // defense in depth that keeps blocking known adult domains even with the cloud filter
            // off or unreachable -- mirrors the Android app's local blocklist being applied
            // client-side regardless of the VPN's cloud-filter state.
            let domainsToBlock = Array(Set(state.blockedDomains).union(AdultBlocklistManager.shared.domains())).sorted()
            if domainsToBlock != self.lastAppliedDomains {
                HostsFileBlocker.apply(domains: domainsToBlock)
                self.lastAppliedDomains = domainsToBlock
            }

            // Resolved (or re-resolved) before pf below, so pf's allowlist reflects this tick's
            // address rather than lagging a tick behind whenever the cloud host's IP changes.
            if state.dnsEnforcementEnabled {
                let now = Date()
                if self.lastDNSCheckAt == nil || now.timeIntervalSince(self.lastDNSCheckAt!) >= self.dnsCheckInterval {
                    DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled)
                    self.lastDNSCheckAt = now
                }
            } else {
                self.lastDNSCheckAt = nil
            }

            let siteBlockActive = !domainsToBlock.isEmpty
            let pfActive = siteBlockActive || state.dnsEnforcementEnabled
            let allowedResolverIPs = DNSEnforcer.lastResolvedIPs
            if pfActive != self.lastAppliedPFActive || allowedResolverIPs != self.lastAppliedAllowedResolverIPs {
                PFBlocker.apply(active: pfActive, allowedResolverIPs: allowedResolverIPs)
                self.lastAppliedPFActive = pfActive
                self.lastAppliedAllowedResolverIPs = allowedResolverIPs
            }

            let killed = AppBlockEnforcer.enforce(blockedApps: state.blockedApps)
            if !killed.isEmpty {
                FileHandle.standardError.write(
                    "[enforcement] killed: \(killed.joined(separator: ", "))\n".data(using: .utf8)!
                )
            }

            if !state.protectedApps.isEmpty {
                let runningExecutables = CommandLineScanner.runningExecutableBasenames()
                for app in state.protectedApps {
                    if !AppProtector.isLocked(bundlePath: app.bundlePath) {
                        AppProtector.lock(bundlePath: app.bundlePath)
                    }

                    let failures = self.consecutiveFailures[app.executableName] ?? 0
                    if let last = self.lastRelaunchAttempt[app.executableName],
                       Date().timeIntervalSince(last) < self.cooldown(forConsecutiveFailures: failures) {
                        continue
                    }

                    switch AppProtector.relaunchIfNeeded(app, runningExecutables: runningExecutables) {
                    case .alreadyRunning:
                        break
                    case .succeeded:
                        self.lastRelaunchAttempt[app.executableName] = Date()
                        self.consecutiveFailures[app.executableName] = 0
                        FileHandle.standardError.write(
                            "[enforcement] relaunched protected app: \(app.displayName)\n".data(using: .utf8)!
                        )
                    case .failed, .noConsoleSession:
                        self.lastRelaunchAttempt[app.executableName] = Date()
                        self.consecutiveFailures[app.executableName] = failures + 1
                    }
                }
            }
        }
    }
}
