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
        return installed
    }

    /// Last-known state without shelling out again -- for status reporting between ticks.
    static var lastKnownState: Bool {
        lastKnownInstalled ?? isInstalled()
    }

    private static func isInstalled() -> Bool {
        let tempPath = NSTemporaryDirectory() + "focuslock-profiles-show-\(UUID().uuidString).plist"
        defer { try? FileManager.default.removeItem(atPath: tempPath) }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/profiles")
        process.arguments = ["show", "-type", "configuration", "-output", tempPath]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return lastKnownInstalled ?? false }
        process.waitUntilExit()

        guard let data = FileManager.default.contents(atPath: tempPath) else { return false }
        // Substring search deliberately over raw bytes, not a parsed plist -- see doc comment.
        return data.range(of: Data(FocusLockConstants.lockProfileIdentifier.utf8)) != nil
    }
}
