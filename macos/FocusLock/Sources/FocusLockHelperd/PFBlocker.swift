import Foundation
import FocusLockShared

/// Secondary defense layer: blocks DNS-over-TLS (port 853) and a handful of well-known public
/// DNS-over-HTTPS resolver IPs so a browser can't sidestep the /etc/hosts redirect (while site
/// blocking is active) or the mandated Cloudflare resolver (while DNS enforcement is on) by
/// resolving through its own encrypted resolver instead of the system one. The IP list
/// deliberately includes Cloudflare's *unfiltered* 1.1.1.1/1.0.0.1 -- when DNS enforcement wants
/// the filtered 1.1.1.3/1.0.0.3, blocking the unfiltered pair closes the obvious "just point at
/// Cloudflare's other IP" bypass. `EnforcementLoop` activates this whenever either site blocking
/// or DNS enforcement is on.
///
/// This is deliberately narrow -- it does not attempt a general-purpose firewall. Rules are
/// loaded into a *named* pf anchor, and /etc/pf.conf is only ever touched to add one marked
/// `anchor`/`load anchor` reference pointing at that anchor, never to replace existing rules.
/// Every write is validated with `pfctl -n` before being applied, and on any failure the daemon
/// leaves the system's existing pf configuration untouched.
enum PFBlocker {
    private static let pfConfPath = "/etc/pf.conf"

    private static let knownDoHResolverIPs = [
        "1.1.1.1", "1.0.0.1", // Cloudflare
        "8.8.8.8", "8.8.4.4", // Google
        "9.9.9.9", "149.112.112.112", // Quad9
        "208.67.222.222", "208.67.220.220", // OpenDNS
    ]

    static func apply(active: Bool) {
        ensureAnchorReferenced()
        writeAnchorRules(active: active)
        reload()
    }

    private static func ensureAnchorReferenced() {
        guard let original = try? String(contentsOfFile: pfConfPath, encoding: .utf8) else { return }
        guard !original.contains(FocusLockConstants.pfConfMarkerBegin) else { return }

        let block = [
            FocusLockConstants.pfConfMarkerBegin,
            "anchor \"\(FocusLockConstants.pfAnchorName)\"",
            "load anchor \"\(FocusLockConstants.pfAnchorName)\" from \"\(FocusLockConstants.pfAnchorFilePath)\"",
            FocusLockConstants.pfConfMarkerEnd,
        ].joined(separator: "\n")

        let candidate = original.trimmingCharacters(in: .newlines) + "\n\n" + block + "\n"
        guard validate(pfConfContent: candidate) else {
            FileHandle.standardError.write("[pf] refusing to modify /etc/pf.conf: candidate failed pfctl -n validation\n".data(using: .utf8)!)
            return
        }
        try? candidate.write(toFile: pfConfPath, atomically: true, encoding: .utf8)
    }

    private static func writeAnchorRules(active: Bool) {
        try? FileManager.default.createDirectory(
            atPath: (FocusLockConstants.pfAnchorFilePath as NSString).deletingLastPathComponent,
            withIntermediateDirectories: true
        )

        guard active else {
            try? "".write(toFile: FocusLockConstants.pfAnchorFilePath, atomically: true, encoding: .utf8)
            return
        }

        // Only block DNS-ish ports on known alternate resolvers -- never "block to <ip>" for
        // all protocols. A full-IP drop on 1.1.1.1/8.8.8.8 wedged browsers whenever anything
        // briefly fell back to those resolvers (or used DoH to them) while system DNS was still
        // settling on Cloudflare Family (1.1.1.3).
        var lines = ["# Managed by FocusLockHelperd -- forces DNS through the system resolver"]
        lines.append("block drop quick proto udp from any to any port 853")
        lines.append("block drop quick proto tcp from any to any port 853")
        for ip in knownDoHResolverIPs {
            lines.append("block drop quick proto udp from any to \(ip) port 53")
            lines.append("block drop quick proto tcp from any to \(ip) port 53")
            lines.append("block drop quick proto tcp from any to \(ip) port 443")
            lines.append("block drop quick proto udp from any to \(ip) port 443")
        }
        let content = lines.joined(separator: "\n") + "\n"
        try? content.write(toFile: FocusLockConstants.pfAnchorFilePath, atomically: true, encoding: .utf8)
    }

    /// Runs `pfctl -n` (dry run/syntax check only, never applies) against a ruleset written to a
    /// scratch temp file so we never validate against -- or worse, apply -- a broken config.
    private static func validate(pfConfContent: String) -> Bool {
        let tempPath = NSTemporaryDirectory() + "focuslock-pf-validate-\(UUID().uuidString).conf"
        try? pfConfContent.write(toFile: tempPath, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/sbin/pfctl")
        process.arguments = ["-n", "-f", tempPath]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try? process.run()
        process.waitUntilExit()
        return process.terminationStatus == 0
    }

    private static func reload() {
        guard validate(pfConfContent: (try? String(contentsOfFile: pfConfPath, encoding: .utf8)) ?? "") else {
            FileHandle.standardError.write("[pf] current /etc/pf.conf fails validation, not reloading\n".data(using: .utf8)!)
            return
        }
        run("/sbin/pfctl", ["-f", pfConfPath])
        run("/sbin/pfctl", ["-E"])
    }

    private static func run(_ path: String, _ args: [String]) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try? process.run()
        process.waitUntilExit()
    }
}
