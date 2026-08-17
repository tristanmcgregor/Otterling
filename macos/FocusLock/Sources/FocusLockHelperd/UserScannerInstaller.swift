import Darwin
import Foundation
import FocusLockShared

/// Pushes the `FocusLockScanner` LaunchAgent into a DIFFERENT user's GUI session, for the "one
/// admin protecting a separate Standard account" deployment shape -- as opposed to the
/// single-account self-accountability model everything else in this app assumes, where the
/// protected user just launches the app themselves and `DaemonRegistrar` registers it under their
/// own session automatically. The daemon already runs as root, so it can write into that user's
/// LaunchAgents directory and bootstrap it directly without needing them to run anything.
///
/// Does NOT and cannot grant Accessibility permission itself -- that's an interactive TCC prompt
/// tied to the target user's own session (Apple's own security model; a third-party daemon can't
/// suppress it without genuine supervised MDM, which this project doesn't have -- see
/// GUARDIAN_SETUP.md). Bootstrapping the agent does cause the scanner to prompt for it
/// automatically the next time that user is at their desktop (`FocusLockScanner.ensureTrusted()`
/// already re-checks and re-prompts on its own), so this gets them to that one unavoidable click
/// instead of requiring them to launch the whole app themselves first.
///
/// NOTE on the DNS-floor lock profile: it is NOT covered by this installer. The server currently
/// provisions one `.mobileconfig` per DEVICE (keyed on the Mac's IOPlatformUUID, not per user
/// account -- see `lockprofile_service.py`'s `build_mobileconfig`), and profile installation itself
/// requires the same kind of interactive click-through as Accessibility (`profiles install` was
/// removed from the command line in macOS 11). Extending it to a second user needs a real design
/// decision (one profile per device vs. one per user account) before it can be automated safely --
/// deliberately not hacked in here.
enum UserScannerInstaller {
    enum InstallError: Error, CustomStringConvertible {
        case userNotFound
        case notLoggedIn
        case writeFailed(String)
        case bootstrapFailed(String)

        var description: String {
            switch self {
            case .userNotFound:
                return "No local user account with that name."
            case .notLoggedIn:
                return "That user has no active GUI session right now -- have them log in once, then run this again."
            case .writeFailed(let reason):
                return "Could not write the LaunchAgent: \(reason)"
            case .bootstrapFailed(let reason):
                return "launchctl bootstrap failed: \(reason)"
            }
        }
    }

    /// Installs (or re-registers) the scanner LaunchAgent for `username`. Requires that user to
    /// currently have an active GUI session -- `launchctl bootstrap gui/<uid>` needs one to attach
    /// to, and reports that clearly rather than silently no-op'ing if they aren't logged in.
    static func install(forUsername username: String) -> Result<String, InstallError> {
        guard let passwd = getpwnam(username) else { return .failure(.userNotFound) }
        let uid = passwd.pointee.pw_uid
        let homeDirectory = String(cString: passwd.pointee.pw_dir)

        guard isLoggedIn(uid: uid) else { return .failure(.notLoggedIn) }

        let sourcePlist = FocusLockConstants.scannerLaunchAgentPlistPath
        let targetDir = "\(homeDirectory)/Library/LaunchAgents"
        let targetPlist = "\(targetDir)/\(FocusLockConstants.scannerBundleIdentifier).plist"

        do {
            try FileManager.default.createDirectory(atPath: targetDir, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: targetPlist) {
                try FileManager.default.removeItem(atPath: targetPlist)
            }
            try FileManager.default.copyItem(atPath: sourcePlist, toPath: targetPlist)
            // Ownership must match the target user, not root, or launchd's gui/<uid> domain refuses
            // to load it (LaunchAgents must be owned by the user whose session they run in).
            try FileManager.default.setAttributes([.ownerAccountID: NSNumber(value: uid)], ofItemAtPath: targetPlist)
        } catch {
            return .failure(.writeFailed(error.localizedDescription))
        }

        // Ignore failure -- fine if it wasn't already loaded (fresh install) or was already
        // unloaded (a re-run after the agent crashed).
        _ = ProcessRunner.run("/bin/launchctl", ["bootout", "gui/\(uid)", targetPlist])
        let bootstrap = ProcessRunner.run("/bin/launchctl", ["bootstrap", "gui/\(uid)", targetPlist])
        // launchctl bootstrap prints nothing to stdout on success; verify by checking the service
        // actually registered rather than trusting silence alone.
        let verify = ProcessRunner.runCapturingStdout(
            "/bin/launchctl", ["print", "gui/\(uid)/\(FocusLockConstants.scannerBundleIdentifier)"]
        )
        guard !verify.isEmpty else {
            return .failure(.bootstrapFailed(bootstrap.output.isEmpty ? "unknown error" : bootstrap.output))
        }

        return .success(
            "Scanner installed and running for \(username) (uid \(uid)). "
                + "They'll see an Accessibility permission prompt next time they're at their desktop -- ask them to click Allow."
        )
    }

    /// The standard root-safe way to detect whether a given uid has an active GUI session: their
    /// loginwindow (or a session-owned Dock, covering fast-user-switch edge cases) is running.
    private static func isLoggedIn(uid: uid_t) -> Bool {
        let output = ProcessRunner.runCapturingStdout("/bin/ps", ["-u", String(uid), "-o", "comm="])
        return output.split(separator: "\n").contains { $0.contains("loginwindow") || $0.hasSuffix("/Dock") }
    }
}
