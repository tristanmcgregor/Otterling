import Darwin
import Foundation
import FocusLockShared

/// Force-quits any running process matching a blocked executable name. Runs as root so it can
/// see and kill processes owned by any user on the machine, not just the caller.
enum AppBlockEnforcer {
    /// Our own binaries, never killable regardless of what ends up in the blocklist -- guards
    /// against a mistaken/malicious entry taking down the enforcement daemon itself. Not
    /// `private`: `DashboardConfigSync` reuses this exact set as its own self-block guard before
    /// ever adding a dashboard-supplied executable name to `blockedApps` -- one list, not two
    /// that can drift, even though this file's own `enforce` already refuses to kill these
    /// regardless.
    static let protectedExecutables: Set<String> = [
        "FocusLockHelperd",
        "FocusLock",
        "focuslockctl",
        // The other two executable targets this package builds (see Package.swift) -- omitted
        // here previously, which meant a blockedApps entry naming either would NOT have been
        // protected. Now more than a local-only risk: a leaked LOCKPROFILE_TOKEN (already
        // accepted as extractable from the shipped binary, see Constants.swift) could otherwise
        // remotely add "FocusLockWatchdog" to blockedApps and have this daemon kill the one
        // process whose entire job is detecting this daemon going away.
        "FocusLockWatchdog",
        "FocusLockScanner",
    ]

    /// Returns the executable names that were actually matched and killed, for logging.
    @discardableResult
    static func enforce(blockedApps: [BlockedApp]) -> [String] {
        guard !blockedApps.isEmpty else { return [] }
        let blockedNames = Set(blockedApps.map { $0.executableName })
            .subtracting(protectedExecutables)
        guard !blockedNames.isEmpty else { return [] }

        var killed: [String] = []
        for process in ProcessScanner.listRunningProcesses() {
            guard blockedNames.contains(process.executableName) else { continue }
            guard !protectedExecutables.contains(process.executableName) else { continue }
            if kill(process.pid, SIGKILL) == 0 {
                killed.append("\(process.executableName)(pid \(process.pid))")
            }
        }
        return killed
    }
}
