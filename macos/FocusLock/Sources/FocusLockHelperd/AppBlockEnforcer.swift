import Darwin
import Foundation
import FocusLockShared

/// Force-quits any running process matching a blocked executable name. Runs as root so it can
/// see and kill processes owned by any user on the machine, not just the caller.
enum AppBlockEnforcer {
    /// Our own binaries, never killable regardless of what ends up in the blocklist -- guards
    /// against a mistaken/malicious entry taking down the enforcement daemon itself.
    private static let protectedExecutables: Set<String> = [
        "FocusLockHelperd",
        "FocusLock",
        "focuslockctl",
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
