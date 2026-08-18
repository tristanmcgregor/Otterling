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
    // See HomeLANState.sample()'s call site above -- computed on the DNS check's cadence, reused by
    // ProxyEnforcer below so both agree without a second network round-trip.
    private var lastHomeLANState = false

    // LockProfileGuard also shells out (to `profiles show`); same reasoning, same cadence.
    private var lastLockProfileCheckAt: Date?
    private let lockProfileCheckInterval: TimeInterval = 15

    // VPNGuard shells out (scutil + route); its own slightly slower cadence -- a VPN coming up a few
    // seconds before it's noticed is fine, and this keeps the tick cheap.
    private var lastVPNCheckAt: Date?
    private let vpnCheckInterval: TimeInterval = 20

    // IntegrityReporter check-in cadence -- a network round-trip, so far slower than the local
    // checks above. 15 minutes matches the phone's own MacTamperPollWorker floor; there's no need
    // for this to be tighter since it's a reporting signal, not something anything else waits on.
    private var lastIntegrityCheckAt: Date?
    private let integrityCheckInterval: TimeInterval = 900

    // ProxyEnforcer shells out to networksetup + does a TCP reachability probe; same "own slower
    // cadence" reasoning as DNS. Re-asserts the system proxy (or removes it when unreachable).
    private var lastProxyCheckAt: Date?
    private let proxyCheckInterval: TimeInterval = 15

    // One-shot: if `state.protectionEnabled` is false when this daemon starts up (e.g. it got
    // manually re-bootstrapped without going through `focuslockctl restore` after a kill switch),
    // make sure DNS/proxy/pf are actually torn down rather than just skipped -- `killSwitch` itself
    // already does this teardown directly, but a daemon starting fresh has no guarantee it's
    // running on a machine `killSwitch` just cleaned up (e.g. a stale live proxy setting from
    // before this specific process started). See `restoreFromKillSwitch` for the only normal path
    // back to `protectionEnabled = true`.
    private var didTearDownForDisabledProtection = false

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

            // Kill-switch state: `protectionEnabled` is only ever set false by
            // `XPCService.killSwitch` and only ever set back to true by
            // `XPCService.restoreFromKillSwitch` (see both for the full picture). Skips
            // everything below -- DNS, proxy, pf, blocked-app kills, protected-app relaunch/lock,
            // even the lock-profile/VPN/integrity monitoring calls -- matching "the whole app is
            // off", not just content filtering. One-shot teardown covers the case where this
            // daemon process started fresh with protection already off (e.g. manually
            // re-bootstrapped outside `focuslockctl restore`) and live DNS/proxy settings from
            // before this process existed are still sitting there unaddressed.
            guard state.protectionEnabled else {
                if !self.didTearDownForDisabledProtection {
                    DNSEnforcer.remove()
                    ProxyEnforcer.apply(host: state.proxyHost, port: state.proxyPort, enabled: false)
                    PFBlocker.apply(active: false)
                    self.didTearDownForDisabledProtection = true
                }
                return
            }
            self.didTearDownForDisabledProtection = false

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
            //
            // HomeLANState.sample() is computed here, once per this same slow cadence, and reused
            // for ProxyEnforcer below too -- one real network round-trip per tick, not two, and DNS
            // and proxy always agree on whether they're home rather than risking one saying yes and
            // the other no in the same tick. See HomeLANState's doc comment for why this value is
            // DEBOUNCED rather than a live per-tick result.
            if state.dnsEnforcementEnabled {
                let now = Date()
                if self.lastDNSCheckAt == nil || now.timeIntervalSince(self.lastDNSCheckAt!) >= self.dnsCheckInterval {
                    self.lastHomeLANState = HomeLANState.sample()
                    DNSEnforcer.apply(cloudHost: state.cloudFilterHost, cloudEnabled: state.cloudFilterEnabled, onHomeLAN: self.lastHomeLANState)
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

            let integrityNow = Date()
            if self.lastIntegrityCheckAt == nil || integrityNow.timeIntervalSince(self.lastIntegrityCheckAt!) >= self.integrityCheckInterval {
                IntegrityReporter.checkIn()
                self.lastIntegrityCheckAt = integrityNow
            }

            // A VPN tunnels traffic around the whole content filter, so it's checked on every tick's
            // slow cadence right alongside the lock profile.
            let vpnNow = Date()
            if self.lastVPNCheckAt == nil || vpnNow.timeIntervalSince(self.lastVPNCheckAt!) >= self.vpnCheckInterval {
                VPNGuard.checkAndReportChanges()
                self.lastVPNCheckAt = vpnNow
            }

            // Proxy enforcement was hardcoded off from 2026-08-17 to 2026-08-18 on suspicion that
            // routing this Mac's web traffic through a proxy on the same home LAN made every
            // downloaded byte cross the home WiFi/LAN link twice (Mac->router->home-server, then
            // home-server->router->Mac again), saturating the link under real load. Investigated
            // with `Scripts/test_proxy_filtering.sh` on 2026-08-18: the theory doesn't hold up.
            // The home server's uplink is wired Gigabit Ethernet (not WiFi), so there's no shared-
            // spectrum contention to begin with; measured Mac-NIC wire/downloaded ratios stayed
            // ~1.0-1.4x with the proxy both on and off (no 2x jump when it turned on); mitmproxy's
            // own CPU stayed low throughout; and download throughput was equal-or-better with the
            // proxy on in every round tested. The severe latency spikes seen during testing showed
            // up in proxy-off phases too, pointing at general home-network variability rather than
            // the proxy. Reverted back to the real Guardian-controlled `state.proxyEnforcementEnabled`
            // (GUI/`focuslockctl enable-proxy`/`disable-proxy`) rather than a maintainer override.
            let proxyEnforcementEnabled = state.proxyEnforcementEnabled
            if proxyEnforcementEnabled {
                let proxyNow = Date()
                if self.lastProxyCheckAt == nil || proxyNow.timeIntervalSince(self.lastProxyCheckAt!) >= self.proxyCheckInterval {
                    self.proxyActive = ProxyEnforcer.apply(
                        host: state.proxyHost, port: state.proxyPort, enabled: true, onHomeLAN: self.lastHomeLANState
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
