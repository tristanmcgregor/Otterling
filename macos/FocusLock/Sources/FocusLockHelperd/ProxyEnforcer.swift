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

        guard let ips = resolveIPv4(target), !ips.isEmpty, isReachable(host: target, port: port) else {
            FileHandle.standardError.write(
                "[proxy] mitmproxy \(target):\(port) is not reachable -- NOT setting the proxy (fail open)\n".data(using: .utf8)!
            )
            remove()
            return false
        }
        lastResolvedProxyIPs = ips

        let user = FocusLockConstants.defaultProxyUser
        let portString = String(port)
        for service in activeNetworkServices() {
            // Skip services already pointed here to avoid re-running networksetup (and re-passing the
            // password on the command line) four times per service on every tick.
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
        for service in activeNetworkServices() {
            ProcessRunner.runSilently("/usr/sbin/networksetup", ["-setwebproxystate", service, "off"])
            ProcessRunner.runSilently("/usr/sbin/networksetup", ["-setsecurewebproxystate", service, "off"])
        }
    }

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
