import Foundation
import FocusLockShared

/// Watches for the lock profile `Scripts/install_lock_profile.py` installs (see
/// `GUARDIAN_SETUP.md` §6) and reports via `TamperReporter` when it disappears. A tripwire, not a
/// removal lock -- see that doc and `filter-server/lockprofile_service.py`'s module docstring for
/// why: macOS honors a local admin's own password over the profile's `RemovalPasscode`, so this
/// can only ever notice removal after the fact, never prevent it.
///
/// `profiles show -type configuration -output <path>` writes a plist (confirmed against a live
/// macOS 15 install: `profiles install` itself was removed in macOS 11, but `show`/`list`/`remove`
/// are all still present and documented). Deliberately doesn't parse that plist's exact key
/// structure -- Apple doesn't publish one and it isn't worth guessing at -- and instead just checks
/// whether the profile identifier string appears anywhere in the raw output, which holds
/// regardless of exactly how the surrounding keys are named or nested.
enum LockProfileGuard {
    // nil until the first check establishes a baseline -- only *transitions* after that get
    // reported, so a fresh daemon start doesn't report "removed" for a profile that was already
    // missing before this process existed (e.g. removed while the daemon itself was unloaded --
    // that gap is real and not closed here; the watchdog reports the unload itself separately).
    private static var lastKnownInstalled: Bool?

    // Same "transitions only" reasoning, for `dnsFloorFunctionallyActive()` below. Stays nil (no
    // report on next definitive reading) whenever a check comes back inconclusive, e.g. away from
    // the home LAN -- see that function's doc comment.
    private static var lastKnownDNSFloorActive: Bool?

    /// Call on the same slower cadence `EnforcementLoop` already uses for DNS
    /// (`dnsCheckInterval`) -- this shells out, so it's not free enough for every tick. Returns the
    /// current installed state so callers (status reporting) don't need a second, separate check.
    @discardableResult
    static func checkAndReportChanges() -> Bool {
        let installed = isInstalled()
        if let last = lastKnownInstalled, last != installed {
            if !installed {
                let message = "lock profile (\(FocusLockConstants.lockProfileIdentifier)) is no " +
                    "longer installed -- DNS floor and removal tripwire are gone"
                FileHandle.standardError.write("[LockProfileGuard] \(message)\n".data(using: .utf8)!)
                TamperReporter.report(type: "lock_profile_removed", details: message)
            } else {
                TamperReporter.report(type: "lock_profile_installed", details: "lock profile detected as installed")
            }
        }
        lastKnownInstalled = installed

        if let floorActive = dnsFloorFunctionallyActive() {
            if let last = lastKnownDNSFloorActive, last != floorActive {
                if !floorActive {
                    let message = "the DNS floor profile is still installed, but its filter was " +
                        "switched off in System Settings > Network > VPN & Filters -- the " +
                        "encrypted-DNS floor is not currently enforced"
                    FileHandle.standardError.write("[LockProfileGuard] \(message)\n".data(using: .utf8)!)
                    TamperReporter.report(type: "dns_floor_disabled", details: message)
                } else {
                    TamperReporter.report(type: "dns_floor_reenabled", details: "DNS floor filter re-enabled")
                }
            }
            lastKnownDNSFloorActive = floorActive
        }

        return installed
    }

    /// Last-known state without shelling out again -- for status reporting between ticks.
    static var lastKnownState: Bool {
        lastKnownInstalled ?? isInstalled()
    }

    private static func isInstalled() -> Bool {
        let tempPath = NSTemporaryDirectory() + "focuslock-profiles-show-\(UUID().uuidString).plist"
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        // This daemon runs as root with no login session of its own. `install_lock_profile.py`'s
        // server-provisioned .mobileconfig installs at the *user* level (confirmed via `profiles
        // list -type=configuration` showing it under the console user, not as a device profile),
        // and plain root `profiles show -type configuration` only enumerates device-level profiles
        // -- it silently omits per-user ones. Without `-user <consoleUser>` this always reports
        // "not installed" even right after a real, successful install.
        var arguments = ["show", "-type", "configuration", "-output", tempPath]
        if let user = consoleUser() {
            arguments += ["-user", user]
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/profiles")
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return lastKnownInstalled ?? false }
        process.waitUntilExit()

        // A transient failure of `/usr/bin/profiles` itself (not the profile actually being
        // removed) must not read as "profile absent" -- that would fire a false tamper alert and
        // flap lastKnownInstalled. Only trust the output when the tool actually succeeded.
        guard process.terminationStatus == 0 else { return lastKnownInstalled ?? false }
        guard let data = FileManager.default.contents(atPath: tempPath) else { return lastKnownInstalled ?? false }
        // Substring search deliberately over raw bytes, not a parsed plist -- see doc comment.
        return data.range(of: Data(FocusLockConstants.lockProfileIdentifier.utf8)) != nil
    }

    /// Functional liveness check for the DNS Settings payload specifically -- macOS's "Filters &
    /// Proxies" pane (System Settings > Network > VPN & Filters) lets a filter be individually
    /// switched to "Disabled" WITHOUT removing the profile at all, and neither `profiles show`'s
    /// human-readable listing nor its raw plist output changes in any way when that happens
    /// (confirmed by hand: byte-identical output enabled vs. disabled) -- `isInstalled()` above
    /// simply cannot see this. Detected functionally instead: this payload points the OS's real DNS
    /// resolution at public Cloudflare Family DoH, which -- having no idea this Mac's filter-server
    /// hostname is on the LAN -- always answers with its public WAN address. `DNSEnforcer`'s own
    /// plain-DNS enforcement, once verified as genuinely talking to the real server (see
    /// `HomeLANVerifier`), instead answers with the LAN address directly. So while confirmed on the
    /// home LAN: if the OS's real resolution path (`dscacheutil`, not `dig`, since it goes through
    /// the same resolution real apps use) still returns the LAN address, whatever public DoH floor
    /// is supposed to be shadowing it isn't running. Returns nil (inconclusive) rather than false
    /// when not confirmed on the home LAN right now -- away from home this specific signal can't
    /// tell "disabled" apart from "working as designed."
    private static func dnsFloorFunctionallyActive() -> Bool? {
        guard HomeLANVerifier.verify(ip: FocusLockConstants.homeLANHost, hostname: FocusLockConstants.defaultCloudFilterHost) else {
            return nil
        }
        guard let resolved = dscacheutilResolve(FocusLockConstants.defaultCloudFilterHost) else { return nil }
        return resolved != FocusLockConstants.homeLANHost
    }

    private static func dscacheutilResolve(_ host: String) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/dscacheutil")
        process.arguments = ["-q", "host", "-a", "name", host]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0, let output = String(data: data, encoding: .utf8) else { return nil }
        for line in output.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("ip_address:") {
                return trimmed.replacingOccurrences(of: "ip_address:", with: "").trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    /// The classic root-safe trick for "who's actually logged in at the console" -- `/dev/console`
    /// is owned by whoever owns the current GUI session, independent of this process's own (root)
    /// identity. Returns nil (rather than "root") if that ownership lookup fails or the machine is
    /// at the login window, so callers fall back to the un-scoped device-level check.
    private static func consoleUser() -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/stat")
        process.arguments = ["-f", "%Su", "/dev/console"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        let user = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let user, !user.isEmpty, user != "root" else { return nil }
        return user
    }
}
