import Foundation

/// Points every active network service's DNS at Cloudflare's content-filtering resolver
/// (1.1.1.3 / 1.0.0.3 -- blocks malware *and* adult content, unlike the plain 1.1.1.1) and keeps
/// reasserting it, the same "check-and-reassert every tick" pattern as everything else in the
/// enforcement loop. Changing DNS back by hand in System Settings only lasts until the next tick.
///
/// This alone isn't enough -- a determined bypass would just set a different resolver via the
/// system's normal UI just as easily as this app does, or a browser could ignore system DNS
/// entirely via its own DNS-over-HTTPS. `PFBlocker` is what actually forecloses those: it drops
/// all traffic to known alternate DoH resolver IPs and to port 853 (DNS-over-TLS) whenever DNS
/// enforcement is on, which forces resolution back through Cloudflare's filtered resolver.
enum DNSEnforcer {
    static let cloudflareFamilyDNS = ["1.1.1.3", "1.0.0.3"]

    static func apply() {
        for service in activeNetworkServices() {
            if currentDNSServers(for: service) != cloudflareFamilyDNS {
                setDNSServers(cloudflareFamilyDNS, for: service)
            }
        }
    }

    static func remove() {
        for service in activeNetworkServices() {
            setDNSServers(["Empty"], for: service)
        }
    }

    /// `networksetup -listallnetworkservices` prints a header line first, then one service name
    /// per line, prefixing disabled services with `*` (which we skip -- nothing to enforce on an
    /// interface that isn't in use).
    private static func activeNetworkServices() -> [String] {
        let output = run("/usr/sbin/networksetup", ["-listallnetworkservices"])
        return output.split(separator: "\n")
            .map(String.init)
            .dropFirst()
            .filter { !$0.hasPrefix("*") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private static func currentDNSServers(for service: String) -> [String] {
        let output = run("/usr/sbin/networksetup", ["-getdnsservers", service])
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("There aren't any DNS Servers") else { return [] }
        return trimmed.split(separator: "\n").map(String.init)
    }

    private static func setDNSServers(_ servers: [String], for service: String) {
        _ = run("/usr/sbin/networksetup", ["-setdnsservers", service] + servers)
    }

    private static func run(_ path: String, _ args: [String]) -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return "" }
        // Read before waiting -- see CommandLineScanner for why the ordering matters.
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return String(data: data, encoding: .utf8) ?? ""
    }
}
