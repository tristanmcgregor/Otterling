import Foundation
import FocusLockShared

/// Enforces the two protections that actually stop a Standard-account user from getting around a
/// protected app (e.g. an accountability app's reporting): the app can't be deleted, and it can't
/// be kept from running.
enum AppProtector {
    /// `schg` (system-immutable) can only be set or cleared by root, unconditionally -- not even
    /// `sudo` from a Standard account helps, since that account has no admin password to give
    /// sudo in the first place. Setting it on the bundle blocks delete/move/rename of the bundle
    /// itself via Finder, `rm`, `mv`, etc.
    static func lock(bundlePath: String) {
        let (status, output) = runCapturingOutput("/usr/bin/chflags", ["schg", bundlePath])
        if status != 0 {
            log("chflags schg failed (status \(status)) for \(bundlePath): \(output)")
        }
    }

    static func unlock(bundlePath: String) {
        let (status, output) = runCapturingOutput("/usr/bin/chflags", ["noschg", bundlePath])
        if status != 0 {
            log("chflags noschg failed (status \(status)) for \(bundlePath): \(output)")
        }
    }

    static func isLocked(bundlePath: String) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/stat")
        process.arguments = ["-f", "%Sf", bundlePath]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return false }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let flags = String(data: data, encoding: .utf8) ?? ""
        return flags.contains("schg")
    }

    enum RelaunchOutcome {
        case alreadyRunning
        case noConsoleSession
        case succeeded
        case failed
    }

    /// Relaunches the app in the console user's GUI session (not root's) if it isn't currently
    /// running (per `runningExecutables`, from `CommandLineScanner`, computed once per
    /// enforcement tick and shared across all protected apps).
    ///
    /// Distinguishes failure from success so the caller can back off exponentially -- e.g. no
    /// console user logged in (locked/logged-out screen) means every attempt fails identically,
    /// and retrying that every few seconds forever floods RunningBoard with launch requests badly
    /// enough to degrade unrelated system services (observed: `sfltool`/`spctl` calls taking 40s+
    /// under that load). Not a problem when the app is actually launchable and just slow to start.
    static func relaunchIfNeeded(_ app: ProtectedApp, runningExecutables: Set<String>) -> RelaunchOutcome {
        guard !runningExecutables.contains(app.executableName.lowercased()) else { return .alreadyRunning }
        guard let uid = ConsoleUser.currentUID() else {
            log("relaunch skipped for \(app.displayName): could not determine console user")
            return .noConsoleSession
        }
        let (status, output) = runCapturingOutput("/bin/launchctl", ["asuser", String(uid), "/usr/bin/open", app.bundlePath])
        if status != 0 {
            log("relaunch of \(app.displayName) failed (status \(status)): \(output)")
            return .failed
        }
        return .succeeded
    }

    private static func log(_ message: String) {
        FileHandle.standardError.write("[AppProtector] \(message)\n".data(using: .utf8)!)
    }

    private static func runCapturingOutput(_ path: String, _ args: [String]) -> (status: Int32, output: String) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        do {
            try process.run()
        } catch {
            return (-1, "\(error)")
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let output = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return (process.terminationStatus, output)
    }
}
