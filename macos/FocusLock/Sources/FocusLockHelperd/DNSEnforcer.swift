import Foundation
import FocusLockShared
import Network

/// Points every active network service's DNS at a cloud content-filter host (a Canopy-style
/// AdGuard Home deployment -- the primary category filter, mirroring the Android app's
/// `CloudFilterSettings`) and keeps reasserting it, the same "check-and-reassert every tick"
/// pattern as everything else in the enforcement loop. Changing DNS back by hand in System
/// Settings only lasts until the next tick.
///
/// Falls back to Cloudflare Family (1.1.1.3/1.0.0.3 -- blocks malware and adult content) only when
/// the cloud filter is turned off or its host can't currently be resolved -- so a cloud outage
/// degrades to a still-reasonable baseline rather than leaving DNS unmanaged. The local adult
/// hosts list (`AdultBlocklistManager`, applied via `HostsFileBlocker` regardless of this toggle)
/// is the actual always-on defense in depth either way.
///
/// This alone isn't enough -- a determined bypass would just set a different resolver via the
/// system's normal UI just as easily as this app does, or a browser could ignore system DNS
/// entirely via its own DNS-over-HTTPS. `PFBlocker` is what actually forecloses those: it drops
/// all traffic to known alternate DoH resolver IPs and to port 853 (DNS-over-TLS) whenever DNS
/// enforcement is on, which forces resolution back through the enforced resolver.
enum DNSEnforcer {
    static let cloudflareFamilyDNS = ["1.1.1.3", "1.0.0.3"]

    /// The cloud filter host's most recently resolved addresses, or empty if the last `apply()`
    /// fell back to Cloudflare Family (host unset, disabled, or unresolved). Read by
    /// `EnforcementLoop` to tell `PFBlocker` which IPs must stay explicitly reachable on :53.
    private(set) static var lastResolvedIPs: [String] = []

    /// Resolves `cloudHost` (when `cloudEnabled`) and points every active network service's DNS at
    /// it; falls back to Cloudflare Family if disabled or resolution fails, rather than leaving
    /// DNS unmanaged. `lanProbePort` is the mitmproxy port on the same filter-server host -- AdGuard
    /// Home itself only answers DNS over UDP (a TCP probe against :53 is refused), so this reuses a
    /// port we know accepts TCP as the "am I on the home LAN right now" signal instead.
    static func apply(cloudHost: String, cloudEnabled: Bool, lanProbePort: Int) {
        let servers: [String]
        // Prefer the known home LAN address when reachable -- see `FocusLockConstants.homeLANHost`'s
        // doc comment: the lock profile's managed DoH setting makes normal hostname resolution for
        // `cloudHost` return its public WAN IP even while on the home network, which this bypasses.
        if cloudEnabled, cloudHost == FocusLockConstants.defaultCloudFilterHost, isPortOpen(host: FocusLockConstants.homeLANHost, port: lanProbePort) {
            servers = [FocusLockConstants.homeLANHost]
            lastResolvedIPs = servers
        } else if cloudEnabled, !cloudHost.isEmpty, let resolved = resolveIPv4(cloudHost), !resolved.isEmpty {
            servers = resolved
            lastResolvedIPs = resolved
        } else {
            if cloudEnabled {
                FileHandle.standardError.write(
                    "[dns] could not resolve cloud filter host '\(cloudHost)' -- falling back to Cloudflare Family\n".data(using: .utf8)!
                )
            }
            servers = cloudflareFamilyDNS
            lastResolvedIPs = []
        }
        for service in activeNetworkServices() {
            if currentDNSServers(for: service) != servers {
                setDNSServers(servers, for: service)
            }
        }
    }

    static func remove() {
        for service in activeNetworkServices() {
            setDNSServers(["Empty"], for: service)
        }
        lastResolvedIPs = []
    }

    /// Resolves a hostname to its IPv4 addresses via the system resolver (`getaddrinfo`) -- this
    /// runs *before* DNS gets pointed at the cloud filter, so it still sees whatever resolver was
    /// configured previously (ISP/router default), not the one this call is about to set.
    private static func resolveIPv4(_ host: String) -> [String]? {
        // A bare IP address needs no resolution -- also lets the host field accept a literal IP.
        if isIPv4Address(host) { return [host] }

        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM

        var resultPointer: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &resultPointer) == 0, let firstResult = resultPointer else {
            return nil
        }
        defer { freeaddrinfo(resultPointer) }

        var addresses: [String] = []
        var current: UnsafeMutablePointer<addrinfo>? = firstResult
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

    /// Short TCP connect probe (AdGuardHome answers TCP as well as UDP on :53) -- same pattern as
    /// `ProxyEnforcer.isReachable`, just duplicated here since the two enforcers are separate
    /// executable-linked types with no shared helper module of their own.
    private static func isPortOpen(host: String, port: Int) -> Bool {
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

    private static func isIPv4Address(_ host: String) -> Bool {
        var addr = in_addr()
        return host.withCString { inet_pton(AF_INET, $0, &addr) } == 1
    }

    /// `networksetup -listallnetworkservices` prints a header line first, then one service name
    /// per line, prefixing disabled services with `*` (which we skip -- nothing to enforce on an
    /// interface that isn't in use).
    private static func activeNetworkServices() -> [String] {
        let output = ProcessRunner.runCapturingStdout("/usr/sbin/networksetup", ["-listallnetworkservices"])
        return output.split(separator: "\n")
            .map(String.init)
            .dropFirst()
            .filter { !$0.hasPrefix("*") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private static func currentDNSServers(for service: String) -> [String] {
        let output = ProcessRunner.runCapturingStdout("/usr/sbin/networksetup", ["-getdnsservers", service])
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("There aren't any DNS Servers") else { return [] }
        return trimmed.split(separator: "\n").map(String.init)
    }

    private static func setDNSServers(_ servers: [String], for service: String) {
        ProcessRunner.runSilently("/usr/sbin/networksetup", ["-setdnsservers", service] + servers)
    }
}
