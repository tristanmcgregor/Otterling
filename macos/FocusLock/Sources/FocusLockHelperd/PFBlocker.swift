import Foundation
import FocusLockShared

/// Secondary defense layer: blocks DNS-over-TLS (port 853) and a handful of well-known public
/// DNS-over-HTTPS resolver IPs so a browser can't sidestep the /etc/hosts redirect (while site
/// blocking is active) or the mandated cloud filter / Cloudflare Family resolver (while DNS
/// enforcement is on) by resolving through its own encrypted resolver instead of the system one.
/// The IP list deliberately includes Cloudflare's *unfiltered* 1.1.1.1/1.0.0.1 -- when DNS
/// enforcement wants a filtered resolver instead, blocking the unfiltered pair closes the obvious
/// "just point at Cloudflare's other IP" bypass. `EnforcementLoop` activates this whenever either
/// site blocking or DNS enforcement is on, and passes it the cloud filter host's currently
/// resolved IPs (`DNSEnforcer.lastResolvedIPs`) so that host is never at risk of being blocked by
/// its own rules.
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

    /// [allowedResolverIPs]: the current cloud filter host's resolved addresses (see
    /// `DNSEnforcer.lastResolvedIPs`) -- explicitly passed on :53 ahead of the block rules below so
    /// the cloud filter itself is never at risk of being self-blocked, even if a future rule change
    /// makes the block list broader or its IP happens to coincide with one already blocked.
    /// [forceProxyActive]/[proxyIPs]/[proxyPort]: when the proxy force-through is on AND the proxy is
    /// confirmed reachable this tick (the caller only ever passes `true` in that case -- see
    /// `EnforcementLoop`/`ProxyEnforcer`), add rules that drop direct outbound :80/:443 to everything
    /// except the proxy, forcing even non-proxy-aware apps through the content filter. This is the one
    /// broad rule in here; it's kept strictly fail-open by that reachability gate, so a down proxy
    /// lifts it within one tick rather than taking web access offline.
    static func apply(
        active: Bool,
        allowedResolverIPs: [String] = [],
        forceProxyActive: Bool = false,
        proxyIPs: [String] = [],
        proxyPort: Int = 0
    ) {
        ensureAnchorReferenced()
        writeAnchorRules(
            active: active,
            allowedResolverIPs: allowedResolverIPs,
            forceProxyActive: forceProxyActive,
            proxyIPs: proxyIPs,
            proxyPort: proxyPort
        )
        reload()
    }

    private static func ensureAnchorReferenced() {
        guard let original = try? String(contentsOfFile: pfConfPath, encoding: .utf8) else { return }
        // Also checks the previous (FocusLock-branded) marker: if an older build already added the
        // anchor reference under that text, skip re-adding it under the new marker -- otherwise
        // pf.conf would end up with the same anchor name declared twice, which fails `pfctl -n`
        // validation and silently blocks every future pf rule change.
        guard !original.contains(FocusLockConstants.pfConfMarkerBegin),
              !original.contains(FocusLockConstants.legacyPFConfMarkerBegin) else { return }

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

    private static func writeAnchorRules(
        active: Bool,
        allowedResolverIPs: [String],
        forceProxyActive: Bool,
        proxyIPs: [String],
        proxyPort: Int
    ) {
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
        // Explicit pass rules for the cloud filter host go first: with `quick`, the first matching
        // rule wins, so these must be evaluated before the block rules below to guarantee the
        // cloud filter itself is always reachable on :53 regardless of what else is blocked there.
        for ip in allowedResolverIPs {
            lines.append("pass quick proto udp from any to \(ip) port 53")
            lines.append("pass quick proto tcp from any to \(ip) port 53")
        }
        lines.append("block drop quick proto udp from any to any port 853")
        lines.append("block drop quick proto tcp from any to any port 853")
        // Port 53 only (plus global 853 DoT above). Do NOT block 443 on these IPs -- Chrome
        // Secure-DNS / DoH in "secure" mode talks to 8.8.8.8:443 / 1.1.1.1:443 and will show
        // DNS_PROBE_STARTED with no fallback if those are dropped. Browser DoH is disabled
        // separately via managed policy so traffic uses system DNS (1.1.1.3) instead.
        for ip in knownDoHResolverIPs {
            lines.append("block drop quick proto udp from any to \(ip) port 53")
            lines.append("block drop quick proto tcp from any to \(ip) port 53")
        }

        // Force-through: drop direct :80/:443 to everything except the proxy, so all web traffic has
        // to go through the content filter. `quick` = first match wins, so the pass rules for the
        // proxy itself (and loopback, so a local server on :443 still works) MUST precede the block
        // drops below. Only emitted when the caller confirmed the proxy is reachable this tick.
        if forceProxyActive, !proxyIPs.isEmpty {
            lines.append("# Force all web traffic through the mitmproxy content filter")
            lines.append("pass quick proto tcp from any to 127.0.0.1")
            lines.append("pass quick proto tcp from any to ::1")
            for ip in proxyIPs {
                if proxyPort > 0 {
                    lines.append("pass quick proto tcp from any to \(ip) port \(proxyPort)")
                }
                // Also let the proxy host itself stay reachable on the normal web ports (its own
                // ACME/renewals, the updates endpoint, etc. share the box).
                lines.append("pass quick proto tcp from any to \(ip) port 80")
                lines.append("pass quick proto tcp from any to \(ip) port 443")
            }
            lines.append("block drop quick proto tcp from any to any port 80")
            lines.append("block drop quick proto tcp from any to any port 443")
            // mitmproxy is an HTTP CONNECT proxy -- it cannot proxy QUIC (HTTP/3 over UDP), so unlike
            // the TCP rule above there's no "pass to the proxy" option for it. Blocking UDP/443
            // outright forces Chrome/Firefox to fall back to standard TLS over TCP, which the proxy
            // rules above do catch -- without this, QUIC would just sail past the content filter
            // entirely on any site that offers it, silently, since a browser prefers QUIC when
            // available and doesn't announce this fallback to the user or the daemon.
            lines.append("block drop quick proto udp from any to any port 443")
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

        return ProcessRunner.runSilently("/sbin/pfctl", ["-n", "-f", tempPath]) == 0
    }

    private static func reload() {
        guard validate(pfConfContent: (try? String(contentsOfFile: pfConfPath, encoding: .utf8)) ?? "") else {
            FileHandle.standardError.write("[pf] current /etc/pf.conf fails validation, not reloading\n".data(using: .utf8)!)
            return
        }
        ProcessRunner.runSilently("/sbin/pfctl", ["-f", pfConfPath])
        ProcessRunner.runSilently("/sbin/pfctl", ["-E"])
    }
}
