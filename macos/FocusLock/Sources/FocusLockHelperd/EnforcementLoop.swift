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
    private let queue = DispatchQueue(label: "app.otterling.enforcement")

    // Tracks what's currently applied to /etc/hosts and pf so reapplyNow() (called on every XPC
    // mutation plus every timer tick) only touches the filesystem/pf when something changed.
    // Domain filtering is entirely server-side now; the daemon only ensures /etc/hosts holds none
    // of its own entries (stripped once per session). See reapplyNow.
    private var didStripHostsBlock = false
    private var lastAppliedPFActive = false
    private var lastAppliedAllowedResolverIPs: [String] = []
    // pf force-through-proxy state, tracked so the anchor is only rewritten when it actually changes.
    private var lastAppliedForceProxyActive = false
    private var lastAppliedProxyIPs: [String] = []

    // Whether the mitmproxy system-proxy is currently set AND reachable (ProxyEnforcer's last verdict).
    // The pf force-through only runs while this is true, so a down proxy can't take web access offline.
    private var proxyActive = false
    // One-shot reconcile: if a previous run left the system proxy set but enforcement is now off,
    // clear it once at startup (the normal disable path goes through PendingActionApplier instead).
    private var didReconcileProxyDisabled = false

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

    // LockProfileGuard also shells out (to `profiles show`); same reasoning, same cadence.
    private var lastLockProfileCheckAt: Date?
    private let lockProfileCheckInterval: TimeInterval = 15

    // VPNGuard shells out (scutil + route); its own slightly slower cadence -- a VPN coming up a few
    // seconds before it's noticed is fine, and this keeps the tick cheap.
    private var lastVPNCheckAt: Date?
    private let vpnCheckInterval: TimeInterval = 20

    // ProxyEnforcer shells out to networksetup + does a TCP reachability probe; same "own slower
    // cadence" reasoning as DNS. Re-asserts the system proxy (or removes it when unreachable).
    private var lastProxyCheckAt: Date?
    private let proxyCheckInterval: TimeInterval = 15

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

            // Before reading state for this tick: apply anything whose cooldown has elapsed, so
            // the rest of the tick enforces the post-change state rather than lagging by up to 3s.
            // Runs off the timer rather than a scheduled wake-up on purpose -- a `PendingAction`
            // matures based on the timestamp persisted in state.json, so a reboot, a daemon
            // restart, or a `launchctl bootout` in the middle of a cooldown neither loses the
            // action nor lets it land early.
            let applied = PendingActionApplier.applyMatured(stateStore: stateStore)
            for action in applied {
                FileHandle.standardError.write(
                    "[cooldown] applied: \(action.describedFully)\n".data(using: .utf8)!
                )
            }

            let state = stateStore.snapshot()

            // All domain filtering now lives on the SERVER (the cloud filter DNS / AdGuard + the
            // dns-classifier). The local /etc/hosts layer is deliberately kept EMPTY: writing large
            // blocklists there once produced a ~4,000,000-line /etc/hosts that crippled mDNSResponder
            // and took the whole machine offline. DNSEnforcer + PFBlocker below force ALL DNS through
            // the server filter, so the server's blocklists apply to everything on this Mac without a
            // single entry written locally. We strip any managed block a previous build left behind
            // once per session (apply([]) is a no-op once /etc/hosts is already clean).
            if !self.didStripHostsBlock {
                HostsFileBlocker.apply(domains: [])
                self.didStripHostsBlock = true
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

            let lockProfileNow = Date()
            if self.lastLockProfileCheckAt == nil || lockProfileNow.timeIntervalSince(self.lastLockProfileCheckAt!) >= self.lockProfileCheckInterval {
                LockProfileGuard.checkAndReportChanges()
                self.lastLockProfileCheckAt = lockProfileNow
            }

            // A VPN tunnels traffic around the whole content filter, so it's checked on every tick's
            // slow cadence right alongside the lock profile.
            let vpnNow = Date()
            if self.lastVPNCheckAt == nil || vpnNow.timeIntervalSince(self.lastVPNCheckAt!) >= self.vpnCheckInterval {
                VPNGuard.checkAndReportChanges()
                self.lastVPNCheckAt = vpnNow
            }

            // Proxy enforcement: point the system HTTP/HTTPS proxy at the filter-server's mitmproxy
            // and keep re-asserting it (own slower cadence). ProxyEnforcer is fail-open -- it returns
            // false and removes the proxy whenever the proxy is unreachable or unprovisioned -- and
            // that verdict (proxyActive) is what gates the pf force-through below, so a down proxy can
            // never wedge web access.
            if state.proxyEnforcementEnabled {
                let proxyNow = Date()
                if self.lastProxyCheckAt == nil || proxyNow.timeIntervalSince(self.lastProxyCheckAt!) >= self.proxyCheckInterval {
                    self.proxyActive = ProxyEnforcer.apply(
                        host: state.proxyHost, port: state.proxyPort, enabled: true
                    )
                    self.lastProxyCheckAt = proxyNow
                }
                self.didReconcileProxyDisabled = false
            } else {
                // Enforcement off: clear any system proxy a previous run left behind, once.
                if !self.didReconcileProxyDisabled {
                    ProxyEnforcer.apply(host: state.proxyHost, port: state.proxyPort, enabled: false)
                    self.didReconcileProxyDisabled = true
                }
                self.proxyActive = false
                self.lastProxyCheckAt = nil
            }

            // pf's jobs: stop DoH/DoT from bypassing the DNS filter (whenever DNS enforcement is on),
            // and -- only when force-through is enabled AND the proxy is confirmed up this tick --
            // drop direct :80/:443 so all web traffic must go through the proxy.
            let forceProxyActive = state.forceProxyViaFirewall && self.proxyActive
            let pfActive = state.dnsEnforcementEnabled || forceProxyActive
            let allowedResolverIPs = DNSEnforcer.lastResolvedIPs
            let proxyIPs = ProxyEnforcer.lastResolvedProxyIPs
            if pfActive != self.lastAppliedPFActive
                || allowedResolverIPs != self.lastAppliedAllowedResolverIPs
                || forceProxyActive != self.lastAppliedForceProxyActive
                || proxyIPs != self.lastAppliedProxyIPs {
                PFBlocker.apply(
                    active: pfActive,
                    allowedResolverIPs: allowedResolverIPs,
                    forceProxyActive: forceProxyActive,
                    proxyIPs: proxyIPs,
                    proxyPort: state.proxyPort
                )
                self.lastAppliedPFActive = pfActive
                self.lastAppliedAllowedResolverIPs = allowedResolverIPs
                self.lastAppliedForceProxyActive = forceProxyActive
                self.lastAppliedProxyIPs = proxyIPs
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
