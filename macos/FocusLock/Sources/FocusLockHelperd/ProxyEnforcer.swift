import Foundation
import FocusLockShared
import Network

/// Points every active network service's system HTTP/HTTPS proxy at the filter-server's mitmproxy
/// and keeps re-asserting it -- the same "check-and-reassert every tick" pattern as `DNSEnforcer`.
/// Turning the proxy off by hand in System Settings only lasts until the next tick. This is what
/// gives the Mac the same server-side content filtering (and trigger-word reporting on blocked
/// pages) the phone already gets by tunnelling through this proxy.
///
/// FAIL-OPEN by construction, because the overriding requirement here is that the filter must never
/// take the machine offline (see the 4M-line /etc/hosts incident). An *authenticated* proxy pointed
/// at an unreachable or wrong endpoint would 407 or hang EVERY web request. So `apply` sets the
/// proxy ONLY when both:
///   1. a proxy password is provisioned (`proxyPasswordPath`, written by setup_mac_proxy.command), and
///   2. the mitmproxy is reachable right now.
/// In every other case it REMOVES the proxy (web falls back to a direct connection) and returns
/// `false`. `EnforcementLoop` uses that return value to decide whether the much more aggressive
/// firewall force-through (`PFBlocker`) may run this tick -- so that, too, is only ever active while
/// the proxy is confirmed up.
enum ProxyEnforcer {
    /// The proxy host's most recently resolved IPv4 addresses (or empty when not enforcing). Read by
    /// `EnforcementLoop` so `PFBlocker` can keep the proxy itself reachable when force-through is on.
    private(set) static var lastResolvedProxyIPs: [String] = []

    /// The actual host `apply` last pointed the system proxy at -- i.e. `host` itself, or
    /// `FocusLockConstants.homeLANHost` when the home-LAN shortcut swapped it in. Empty when not
    /// enforcing. `EnforcementLoop` reads this for `ShellProxyEnvManager` so CLI tools' `HTTPS_PROXY`
    /// matches the SAME address the system-wide GUI proxy actually uses -- passing `state.proxyHost`
    /// there instead always aimed shell tools at the public hostname even on home LAN, where it
    /// doesn't NAT-hairpin back to the home server, producing a connection that hangs until timeout
    /// while the browser (correctly pointed at the LAN IP) worked fine.
    private(set) static var lastAppliedHost: String = ""

    /// Debounced reachability signal for the current `target` -- same pattern (and same
    /// `requiredConsecutiveSamples` threshold) as `HomeLANState`, added for the same class of
    /// reason: a single slow/dropped 2s TCP probe against a proxy that's actually fine was flipping
    /// the *system-wide* HTTP/HTTPS proxy off and back on almost every tick, and each flip runs
    /// `networksetup -setwebproxystate ... off/on`, which silently drops every connection currently
    /// tunnelled through the proxy -- including whatever the user is mid-download on. A proxy that's
    /// merely flaky must ride out a few bad probes before this tears the system proxy down; one
    /// that's genuinely down still fails open within a few ticks, same as before.
    private static let requiredConsecutiveReachabilitySamples = 3
    // Independent, absolute-time backstop on top of the consecutive-sample counting below -- not a
    // substitute for it, a guard against whatever pattern the streak counting itself doesn't
    // anticipate. The streak logic already fixed one such pattern (see the comment below) by
    // construction, but "guaranteed to fail open within N seconds no matter what" is a much easier
    // safety property to hold onto than "correct for every conceivable sequence of raw samples,"
    // and this is the machine's ENTIRE internet access on the line. If it's been this long since the
    // last raw success, force the debounced state down regardless of streak state.
    private static let maxStaleReachableInterval: TimeInterval = 90
    private static var lastProbedTarget: String?
    private static var debouncedReachableState = false
    private static var consecutiveReachable = 0
    private static var consecutiveUnreachable = 0
    private static var lastRawSuccessAt: Date?

    private static func debouncedReachable(host: String, port: Int) -> Bool {
        // A different target (e.g. home-LAN vs. public host swap) has no bearing on the previous
        // target's reachability history -- start that debounce fresh rather than carrying over a
        // stale streak that was measuring a different address entirely.
        let key = "\(host):\(port)"
        if key != lastProbedTarget {
            lastProbedTarget = key
            debouncedReachableState = false
            consecutiveReachable = 0
            consecutiveUnreachable = 0
            lastRawSuccessAt = nil
        }

        // Streaks are counted against the RAW signal's own run, never against "does this sample
        // match what we currently believe" -- confirmed live 2026-09-02: the previous version reset
        // its one shared counter to 0 every time a sample agreed with the CURRENT debounced state,
        // which means once that state was `true` (proxy up), any interspersed success reset the
        // failure count to 0 before it could reach the threshold. A proxy that's degraded rather
        // than cleanly down -- e.g. succeeding on only every third probe -- produced exactly that
        // pattern (fail, fail, succeed, fail, fail, succeed, ...) and NEVER accumulated 3 consecutive
        // failures, so `debouncedReachableState` stayed stuck at "reachable" forever: the system
        // proxy (and firewall force-through) stayed on indefinitely while most real traffic silently
        // hung, with no automatic recovery -- the fail-open this debounce exists to preserve never
        // fired. Tracking each direction's own streak independently means 3 consecutive raw failures
        // flip it regardless of which direction the currently-held state happens to be biased toward.
        if isReachable(host: host, port: port) {
            consecutiveReachable += 1
            consecutiveUnreachable = 0
            lastRawSuccessAt = Date()
        } else {
            consecutiveUnreachable += 1
            consecutiveReachable = 0
        }

        if !debouncedReachableState, consecutiveReachable >= requiredConsecutiveReachabilitySamples {
            debouncedReachableState = true
        } else if debouncedReachableState, consecutiveUnreachable >= requiredConsecutiveReachabilitySamples {
            debouncedReachableState = false
        }

        if debouncedReachableState,
           let lastSuccess = lastRawSuccessAt,
           Date().timeIntervalSince(lastSuccess) > maxStaleReachableInterval {
            debouncedReachableState = false
        }
        return debouncedReachableState
    }

    /// Returns true iff the system proxy is now set AND pointed at a reachable mitmproxy. False means
    /// "not enforcing" (disabled, no password, or unreachable) and the proxy has been removed.
    /// `onHomeLAN` is `HomeLANState`'s DEBOUNCED signal -- see that type's doc comment for the
    /// 2026-08-17 reload-storm incident this parameter exists to prevent a repeat of. Only used to
    /// pick the target when `host` is the default cloud filter host; a custom host is always used
    /// as-is.
    @discardableResult
    static func apply(host: String, port: Int, enabled: Bool, onHomeLAN: Bool = false) -> Bool {
        guard enabled else {
            remove()
            return false
        }

        guard let password = readProxyPassword(), !password.isEmpty else {
            FileHandle.standardError.write(
                "[proxy] enforcement is on but no proxy password is provisioned (\(FocusLockConstants.proxyPasswordPath)) -- NOT setting the proxy (fail open). Run Scripts/setup_mac_proxy.command.\n"
                    .data(using: .utf8)!
            )
            remove()
            return false
        }

        let target = (host == FocusLockConstants.defaultCloudFilterHost && onHomeLAN) ? FocusLockConstants.homeLANHost : host

        guard let ips = resolveIPv4(target), !ips.isEmpty, debouncedReachable(host: target, port: port) else {
            FileHandle.standardError.write(
                "[proxy] mitmproxy \(target):\(port) is not reachable -- NOT setting the proxy (fail open)\n".data(using: .utf8)!
            )
            remove()
            return false
        }
        lastResolvedProxyIPs = ips
        lastAppliedHost = target

        let user = FocusLockConstants.defaultProxyUser
        let portString = String(port)
        for service in activeNetworkServices() {
            // Skip services already pointed here to avoid re-running networksetup (and re-passing
            // the password on the command line) four times per service on every tick.
            //
            // On the argv exposure: `networksetup -setwebproxy` takes the credential positionally
            // and offers no stdin or keychain path, so unlike `otterlingctl` (which deliberately
            // refuses to accept the Guardian passcode as an argument) this one cannot be moved off
            // argv without abandoning networksetup entirely. What actually bounds it: macOS
            // restricts reading another process's arguments to the same uid or root, and this
            // daemon is root -- so a Standard account cannot see it, and an admin account could
            // become root anyway. Minimising the number of invocations is therefore the whole
            // available mitigation, which is what the skip below is for.
            guard !isProxyAlreadySet(service: service, host: target, port: port) else { continue }
            ProcessRunner.runSilently("/usr/sbin/networksetup",
                ["-setwebproxy", service, target, portString, "on", user, password])
            ProcessRunner.runSilently("/usr/sbin/networksetup",
                ["-setsecurewebproxy", service, target, portString, "on", user, password])
        }
        return true
    }

    static func remove() {
        lastResolvedProxyIPs = []
        lastAppliedHost = ""
        for service in activeNetworkServices() {
            ProcessRunner.runSilently("/usr/sbin/networksetup", ["-setwebproxystate", service, "off"])
            ProcessRunner.runSilently("/usr/sbin/networksetup", ["-setsecurewebproxystate", service, "off"])
        }
    }

    /// Exposed so `ShellProxyEnvManager` can give shell-based CLI tools the exact same credential
    /// `apply` already uses for the system-wide GUI proxy setting, rather than a second copy.
    static func currentProxyPassword() -> String? { readProxyPassword() }

    private static func readProxyPassword() -> String? {
        guard let data = FileManager.default.contents(atPath: FocusLockConstants.proxyPasswordPath) else { return nil }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A short TCP connect probe -- ready within the timeout means the proxy is up. Deliberately the
    /// gate for setting the proxy at all, so a down proxy never wedges web access.
    private static func isReachable(host: String, port: Int) -> Bool {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return false }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        let semaphore = DispatchSemaphore(value: 0)
        var reachable = false
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                reachable = true
                semaphore.signal()
            case .failed, .cancelled:
                reachable = false
                semaphore.signal()
            default:
                break
            }
        }
        connection.start(queue: .global())
        _ = semaphore.wait(timeout: .now() + 2)
        connection.cancel()
        return reachable
    }

    /// Parses `networksetup -getwebproxy` so we only re-set a service whose proxy has drifted from
    /// what we want (or been turned off by hand).
    private static func isProxyAlreadySet(service: String, host: String, port: Int) -> Bool {
        let output = ProcessRunner.runCapturingStdout("/usr/sbin/networksetup", ["-getwebproxy", service])
        var enabled = false
        var server = ""
        var currentPort = ""
        for line in output.split(separator: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count == 2 else { continue }
            switch parts[0] {
            case "Enabled": enabled = parts[1].lowercased() == "yes"
            case "Server": server = parts[1]
            case "Port": currentPort = parts[1]
            default: break
            }
        }
        return enabled && server == host && currentPort == String(port)
    }

    // `getaddrinfo` has no built-in timeout and depends on whatever resolver is *currently*
    // configured -- which could be this app's own previous choice, now unreachable after a network
    // change. Hard-bounded on a background thread so a dead resolver can never stall this past a
    // few seconds -- see `DNSEnforcer.resolveIPv4`'s doc comment for the incident this addresses.
    private static let resolveTimeout: TimeInterval = 3

    private static func resolveIPv4(_ host: String) -> [String]? {
        let semaphore = DispatchSemaphore(value: 0)
        var result: [String]?
        DispatchQueue.global().async {
            result = resolveIPv4Blocking(host)
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + resolveTimeout)
        return result
    }

    private static func resolveIPv4Blocking(_ host: String) -> [String]? {
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM

        var resultPointer: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &resultPointer) == 0, let first = resultPointer else { return nil }
        defer { freeaddrinfo(resultPointer) }

        var addresses: [String] = []
        var current: UnsafeMutablePointer<addrinfo>? = first
        while let entry = current {
            defer { current = entry.pointee.ai_next }
            guard let aiAddr = entry.pointee.ai_addr else { continue }
            var addr = sockaddr_in()
            withUnsafeMutableBytes(of: &addr) { destination in
                destination.copyMemory(from: UnsafeRawBufferPointer(start: aiAddr, count: MemoryLayout<sockaddr_in>.size))
            }
            if let cString = inet_ntoa(addr.sin_addr) {
                let ip = String(cString: cString)
                if !addresses.contains(ip) { addresses.append(ip) }
            }
        }
        return addresses
    }

    /// Same active-service enumeration `DNSEnforcer` uses.
    private static func activeNetworkServices() -> [String] {
        let output = ProcessRunner.runCapturingStdout("/usr/sbin/networksetup", ["-listallnetworkservices"])
        return output.split(separator: "\n")
            .map(String.init)
            .dropFirst()
            .filter { !$0.hasPrefix("*") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }
}
