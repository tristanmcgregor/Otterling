import Foundation
import FocusLockShared
import Network

/// Points every active network service's DNS at a cloud content-filter host (a Canopy-style
/// AdGuard Home deployment -- the primary category filter, mirroring the Android app's
/// `CloudFilterSettings`) and keeps reasserting it, the same "check-and-reassert every tick"
/// pattern as everything else in the enforcement loop. Changing DNS back by hand in System
/// Settings only lasts until the next tick.
///
/// Falls back to Cloudflare Family (1.1.1.3/1.0.0.3 -- blocks malware and adult content) whenever the
/// cloud filter is turned off, its host can't currently be resolved, OR the resolved server fails a
/// live UDP DNS probe (`isDNSReachable`) -- so a cloud *outage* (server merely offline, hostname still
/// resolving fine) degrades to a still-reasonable baseline exactly the same as an unresolvable host,
/// rather than pointing every network service's DNS at a dead server and leaving the Mac with no
/// internet until it comes back. Mirrors `ProxyEnforcer`'s fail-open reachability gate. The local
/// adult hosts list (`AdultBlocklistManager`, applied via `HostsFileBlocker` regardless of this
/// toggle) is the actual always-on defense in depth either way.
///
/// This alone isn't enough -- a determined bypass would just set a different resolver via the
/// system's normal UI just as easily as this app does, or a browser could ignore system DNS
/// entirely via its own DNS-over-HTTPS. `PFBlocker` NARROWS those: it drops traffic to a list of
/// known public DoH/DoT resolver IPs and to port 853 (DNS-over-TLS) whenever DNS enforcement is on,
/// which pushes resolution back through the enforced resolver for every resolver on that list.
///
/// It does not CLOSE them, and this comment used to say it did. An IP denylist cannot cover a
/// custom DoH endpoint hosted on a shared CDN address, because blocking that address would break
/// unrelated web traffic on the same IP. A browser configured with such an endpoint still resolves
/// around this. The durable answer is browser policy (a managed preference forcing DoH off), not a
/// firewall rule; on the phone side the same gap is why `mitm_nsfw_addon.py`'s content inspection
/// matters so much more than the DNS tier. Recorded plainly here because other parts of this
/// design were reasoning from the stronger claim.
enum DNSEnforcer {
    static let cloudflareFamilyDNS = ["1.1.1.3", "1.0.0.3"]

    /// The cloud filter host's most recently resolved addresses, or empty if the last `apply()`
    /// fell back to Cloudflare Family (host unset, disabled, or unresolved). Read by
    /// `EnforcementLoop` to tell `PFBlocker` which IPs must stay explicitly reachable on :53.
    private(set) static var lastResolvedIPs: [String] = []

    /// Whether the last `apply()` reached the cloud filter host itself, as opposed to falling back
    /// to Cloudflare Family. `true` when disabled (nothing to be unreachable) so this only ever
    /// reads as a problem when it's actually one. Overlaid into `FocusLockState.cloudFilterHostReachable`
    /// by `XPCService.getStatus` (see that field's doc comment) so the GUI can alert on the
    /// transition instead of this only ever showing up as a repeating stderr line nobody's watching
    /// -- which is exactly how today's backend outage went unnoticed until symptoms turned up
    /// elsewhere entirely (a hung update check, a stuck proxy) instead of at the source.
    private(set) static var cloudFilterHostReachable = true

    /// Resolves `cloudHost` (when `cloudEnabled`) and points every active network service's DNS at
    /// it; falls back to Cloudflare Family if disabled or resolution fails, rather than leaving
    /// DNS unmanaged. `onHomeLAN` is `HomeLANState`'s DEBOUNCED signal (see that type's doc comment
    /// for the 2026-08-17 reload-storm incident this exists to prevent) -- when true, uses the LAN
    /// IP directly rather than resolving the hostname (which the lock profile's managed DoH setting
    /// would otherwise shadow with a public answer even while on the home network).
    static func apply(cloudHost: String, cloudEnabled: Bool, onHomeLAN: Bool = false) {
        let servers: [String]
        if cloudEnabled, cloudHost == FocusLockConstants.defaultCloudFilterHost, onHomeLAN,
           isDNSReachable(host: FocusLockConstants.homeLANHost) {
            servers = [FocusLockConstants.homeLANHost]
            lastResolvedIPs = servers
            cloudFilterHostReachable = true
        } else if cloudEnabled, !cloudHost.isEmpty, let resolved = resolveIPv4(cloudHost), !resolved.isEmpty,
                  isDNSReachable(host: resolved[0]) {
            servers = resolved
            lastResolvedIPs = resolved
            cloudFilterHostReachable = true
        } else {
            if cloudEnabled {
                FileHandle.standardError.write(
                    "[dns] cloud filter host '\(cloudHost)' unresolved or unreachable -- falling back to Cloudflare Family\n".data(using: .utf8)!
                )
            }
            servers = cloudflareFamilyDNS
            lastResolvedIPs = []
            cloudFilterHostReachable = !cloudEnabled
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
    /// runs *before* DNS gets pointed at the cloud filter, so it still sees whatever resolver is
    /// *currently* configured. That's a real hazard, not just a quirk: if the current resolver is
    /// this app's own previous choice (e.g. `homeLANHost`, left set from being on the home network)
    /// and the Mac has since moved to a different network where that address is unreachable,
    /// `getaddrinfo` has no built-in timeout and can hang far longer than this loop's 15s reassert
    /// cadence assumes -- which stalls DNS recovery and can look exactly like "no internet" for
    /// much longer than intended. Hard-bounded to `resolveTimeout` on a background thread for
    /// exactly that reason -- a slow/dead resolver must never block this past a few seconds,
    /// because the whole point of this function's caller is to fix a broken resolver, not get stuck
    /// waiting on it.
    private static let resolveTimeout: TimeInterval = 3

    private static func resolveIPv4(_ host: String) -> [String]? {
        // A bare IP address needs no resolution -- also lets the host field accept a literal IP.
        if isIPv4Address(host) { return [host] }

        // The result crosses threads, so it goes through a lock rather than a bare `var`. On
        // timeout this function returns while the background getaddrinfo is still running, and that
        // thread will still write its answer -- an unsynchronized Optional<[String]> read here
        // against that write is a genuine data race, in root-privileged code, on the path whose
        // whole job is recovering from a broken resolver.
        final class Box {
            private let lock = NSLock()
            private var value: [String]?
            func set(_ newValue: [String]?) {
                lock.lock(); defer { lock.unlock() }
                value = newValue
            }
            func get() -> [String]? {
                lock.lock(); defer { lock.unlock() }
                return value
            }
        }
        let box = Box()
        let semaphore = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            box.set(resolveIPv4Blocking(host))
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + resolveTimeout)
        return box.get()
    }

    private static func resolveIPv4Blocking(_ host: String) -> [String]? {
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

    private static func isIPv4Address(_ host: String) -> Bool {
        var addr = in_addr()
        return host.withCString { inet_pton(AF_INET, $0, &addr) } == 1
    }

    /// A short UDP DNS liveness probe (mirrors `ProxyEnforcer.isReachable`, and
    /// `FocusLockShared.CloudFilterProbe.testReachable` used by the GUI's "test connection" button)
    /// gating whether `apply()` actually points system DNS at the resolved cloud filter server. Without
    /// this, a server that's merely offline but whose hostname still resolves (or a stale `homeLANHost`
    /// while still "on the home LAN") gets set as the DNS server anyway, and every DNS query on the
    /// machine times out until the server comes back -- the same fail-open reasoning as `ProxyEnforcer`,
    /// just applied to DNS instead of the web proxy.
    private static let probeTimeout: TimeInterval = 2

    private static func isDNSReachable(host: String) -> Bool {
        guard let port = NWEndpoint.Port(rawValue: 53) else { return false }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .udp)
        let lock = NSLock()
        var finished = false
        var reachable = false
        let semaphore = DispatchSemaphore(value: 0)
        let finish: (Bool) -> Void = { ok in
            lock.lock()
            defer { lock.unlock() }
            guard !finished else { return }
            finished = true
            reachable = ok
            semaphore.signal()
        }
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                connection.send(content: buildDNSProbeQuery(), completion: .contentProcessed { _ in })
                connection.receiveMessage { data, _, _, error in
                    finish(data != nil && error == nil)
                }
            case .failed, .cancelled:
                finish(false)
            default:
                break
            }
        }
        connection.start(queue: .global())
        _ = semaphore.wait(timeout: .now() + probeTimeout)
        connection.cancel()
        return reachable
    }

    /// Minimal standalone "A" query for `example.com`, matching `CloudFilterProbe.buildQuery`.
    private static func buildDNSProbeQuery() -> Data {
        var bytes: [UInt8] = [
            0x12, 0x34, // arbitrary transaction ID
            0x01, 0x00, // flags: standard query, recursion desired
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, // ANCOUNT
            0x00, 0x00, // NSCOUNT
            0x00, 0x00, // ARCOUNT
        ]
        for label in "example.com".split(separator: ".") {
            bytes.append(UInt8(label.utf8.count))
            bytes.append(contentsOf: Array(label.utf8))
        }
        bytes.append(0) // root label
        bytes.append(contentsOf: [0x00, 0x01, 0x00, 0x01]) // QTYPE=A, QCLASS=IN
        return Data(bytes)
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

    // `networksetup`'s writes go through configd's SCPreferences commit, which can transiently
    // refuse a write ("Unable to commit changes to network database", exit 7) under contention --
    // confirmed live 2026-09-03: with DNSEnforcer/ProxyEnforcer/PFBlocker all reasserting settings
    // on their own tick cadences, configd got wedged into refusing EVERY commit (not just DNS, not
    // just one service) for the rest of the session, which is indistinguishable from "no internet"
    // and previously required a full reboot to clear since nothing -- not even killSwitch's own
    // teardown -- could ever get a write to stick once wedged. A short bounded retry is the known,
    // much cheaper recovery for this specific transient lock than the reboot this used to demand.
    private static let commitRetryDelays: [TimeInterval] = [0.3, 0.8, 1.5]

    private static func setDNSServers(_ servers: [String], for service: String) {
        var result = ProcessRunner.run("/usr/sbin/networksetup", ["-setdnsservers", service] + servers)
        for delay in commitRetryDelays where result.status != 0 {
            Thread.sleep(forTimeInterval: delay)
            result = ProcessRunner.run("/usr/sbin/networksetup", ["-setdnsservers", service] + servers)
        }
        // Was `runSilently` -- a failure here (wrong service name, networksetup erroring) used to
        // be completely invisible, which is exactly how a real bug in `remove()` went unnoticed
        // during kill-switch testing. Log any non-zero exit (even after retrying) so a silent
        // failure can't happen again.
        if result.status != 0 {
            FileHandle.standardError.write(
                "[dns] setdnsservers '\(service)' \(servers) failed (exit \(result.status)) after \(commitRetryDelays.count) retries: \(result.output)\n".data(using: .utf8)!
            )
        }
    }
}
