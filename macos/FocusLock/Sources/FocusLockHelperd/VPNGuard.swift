import Foundation
import FocusLockShared

/// Watches for a VPN carrying the machine's traffic and reports via `TamperReporter` when one comes
/// up. This is the one hole that defeats the *content filter itself* rather than just the alerting:
/// the DNS floor, `/etc/hosts`, and pf allowlist only govern the system resolver and its routes, so
/// a VPN app tunnels every request around all of it. macOS gives a local admin no way to forbid
/// installing a VPN (there's no per-user NetworkExtension lockdown without supervision -- see
/// GUARDIAN_SETUP.md), so, exactly like `LockProfileGuard`, this can only ever notice after the
/// fact, never prevent. Detection + a loud alert is the whole contract.
///
/// Detection is deliberately a two-signal heuristic chosen to catch the real bypass without
/// false-positiving on Apple's own tunnels:
///   1. `scutil --nc list` reporting a service `(Connected)` -- covers the built-in IKEv2/L2TP/IPsec
///      and any NetworkExtension VPN that registers as a network-configuration service.
///   2. The *default route* leaving over a tunnel interface (`utun`/`ipsec`/`ppp`/`tun`/`tap`) --
///      covers app-based VPNs (WireGuard, OpenVPN, WARP, Tailscale-as-exit) that don't show in
///      `scutil --nc list` but do become the path all traffic takes.
///
/// Signal 2 keys on the *default* route on purpose: macOS keeps `utun0..utun3` up at all times for
/// Handoff/AirDrop/iCloud Relay even with no VPN, but those are never the default route, so testing
/// "is the default route a tunnel" ignores them while still catching a real full-tunnel VPN.
enum VPNGuard {
    // nil until the first check establishes a baseline -- only *transitions* into "VPN up" are
    // reported after that, so a daemon start while a VPN is already connected reports once (the
    // baseline is nil -> true), and steady-state connected ticks don't re-alert every 20s.
    private static var lastKnownActive: Bool?

    /// Called on its own slow cadence from `EnforcementLoop` (this shells out twice, so it isn't
    /// free enough for every 3s tick). Returns the current state so status reporting can reuse it.
    @discardableResult
    static func checkAndReportChanges() -> Bool {
        let active = isVPNActive()
        if lastKnownActive != active {
            if active {
                let via = activeTunnelDescription() ?? "a VPN tunnel"
                let message = "traffic is routing through \(via) -- the content filter (DNS floor + "
                    + "hosts + pf) is bypassed while this is up"
                FileHandle.standardError.write("[VPNGuard] \(message)\n".data(using: .utf8)!)
                TamperReporter.report(type: "vpn_active", details: message)
            } else if lastKnownActive == true {
                // Only report the clear when we'd previously reported it up -- not on the nil->false
                // first-tick baseline, which is the normal no-VPN state and not worth an alert.
                TamperReporter.report(type: "vpn_cleared", details: "VPN tunnel no longer carries traffic")
            }
        }
        lastKnownActive = active
        return active
    }

    /// Last-known state without shelling out again -- for status reporting between ticks.
    static var lastKnownState: Bool {
        lastKnownActive ?? false
    }

    private static func isVPNActive() -> Bool {
        connectedNetworkService() || defaultRouteIsTunnel()
    }

    /// A short human description of what's up, for the alert body. Prefers the named service.
    private static func activeTunnelDescription() -> String? {
        if let name = connectedServiceName() { return "VPN \"\(name)\"" }
        if let iface = defaultRouteTunnelInterface() { return "tunnel interface \(iface)" }
        return nil
    }

    // MARK: - Signal 1: scutil --nc list

    private static func connectedNetworkService() -> Bool {
        connectedServiceName() != nil
    }

    /// Parses `scutil --nc list`. Lines look like:
    ///   * (Connected)   0123ABCD-... IPSec "Work VPN" [IPSec:...]
    ///   * (Disconnected) ...
    /// We return the quoted display name of the first `(Connected)` entry.
    private static func connectedServiceName() -> String? {
        guard let output = runTool("/usr/sbin/scutil", ["--nc", "list"]) else { return nil }
        for line in output.split(separator: "\n") where line.contains("(Connected)") {
            // The display name is the first double-quoted field on the line.
            if let open = line.firstIndex(of: "\""),
               let close = line[line.index(after: open)...].firstIndex(of: "\"") {
                return String(line[line.index(after: open)..<close])
            }
            return "" // Connected but unnamed -- still counts as active.
        }
        return nil
    }

    // MARK: - Signal 2: default route over a tunnel

    private static let tunnelPrefixes = ["utun", "ipsec", "ppp", "tun", "tap"]

    private static func defaultRouteIsTunnel() -> Bool {
        defaultRouteTunnelInterface() != nil
    }

    /// `route -n get default` prints an `interface: <name>` line for the current default route.
    /// Returns that interface name iff it's a tunnel device.
    private static func defaultRouteTunnelInterface() -> String? {
        guard let output = runTool("/sbin/route", ["-n", "get", "default"]) else { return nil }
        for line in output.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("interface:") else { continue }
            let iface = trimmed.replacingOccurrences(of: "interface:", with: "").trimmingCharacters(in: .whitespaces)
            return tunnelPrefixes.contains(where: iface.hasPrefix) ? iface : nil
        }
        return nil
    }

    // MARK: - Process helper

    /// Runs a tool and returns stdout, or nil if it couldn't run or exited non-zero -- a transient
    /// failure of the tool must read as "unknown", never as a spurious state flip (same discipline
    /// as `LockProfileGuard.isInstalled`).
    private static func runTool(_ path: String, _ arguments: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
