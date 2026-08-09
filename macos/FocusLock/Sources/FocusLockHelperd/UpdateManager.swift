import CryptoKit
import Foundation
import FocusLockShared

/// Fetches, verifies, and installs Otterling updates -- macOS counterpart to the Android app's
/// `ApprovedUpdateManager`, same trust chain, in the same order:
///   1. The manifest names a version/downloadUrl/sha256.
///   2. The downloaded file's own SHA-256 must match the manifest's.
///   3. The extracted `.app` bundle's code signature must verify, and its Team Identifier must
///      match `FocusLockConstants.pinnedUpdateTeamID` -- baked in at build time, empty by default.
///      **Refuses to install anything while that's empty** -- fail closed, same stance Android
///      takes for a build with no `RELEASE_CERT_SHA256`. A compromised update host could publish a
///      resigned bundle with a matching self-authored manifest (passing steps 1-2), but it can't
///      forge a Team Identifier it doesn't hold the signing key for -- that's the actual root of
///      trust, not the manifest.
///
/// Runs entirely synchronously (blocking) -- same reasoning as `AdultBlocklistManager`'s
/// synchronous downloads: callers dispatch this onto its own background queue, never the
/// enforcement loop's or the XPC listener's.
///
/// See `filter-server/updates/README.md` / `macos/FocusLock/RELEASE.md` for how a manifest +
/// signed build actually gets published -- there's no automated CI path for macOS yet (the
/// existing AI-gated release host is Linux-only, can't build/sign a `.app`), so this is a
/// manual/local publish step for now.
enum UpdateManager {
    static func checkForUpdate(host: String) -> UpdateCheckStatus {
        guard let manifest = fetchManifest(host: host) else {
            return .error("Could not reach or parse the update manifest at \(host)")
        }
        if manifest.versionCode <= FocusLockConstants.appVersionCode {
            return .upToDate
        }
        return .updateAvailable(manifest)
    }

    static func downloadVerifyAndInstall(_ manifest: UpdateManifest) -> UpdateInstallResult {
        let pinnedTeamID = FocusLockConstants.pinnedUpdateTeamID
        guard !pinnedTeamID.isEmpty else {
            return .rejected(
                "This build has no pinned update Team ID (FocusLockConstants.pinnedUpdateTeamID) " +
                "-- refusing to install any update. See that constant's doc comment."
            )
        }

        let stagingDir = FocusLockConstants.updateStagingDirectory
        try? FileManager.default.removeItem(atPath: stagingDir)
        do {
            try FileManager.default.createDirectory(
                atPath: stagingDir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]
            )
        } catch {
            return .rejected("Could not create staging directory: \(error)")
        }
        defer { try? FileManager.default.removeItem(atPath: stagingDir) }

        let zipPath = "\(stagingDir)/update.zip"
        guard downloadFile(urlString: manifest.downloadUrl, toPath: zipPath) else {
            return .rejected("Download failed")
        }

        let actualSHA256 = sha256OfFile(atPath: zipPath)
        guard let actualSHA256, actualSHA256.caseInsensitiveCompare(manifest.sha256) == .orderedSame else {
            return .rejected("SHA-256 mismatch -- refusing to install")
        }

        guard unzip(zipPath: zipPath, into: stagingDir) else {
            return .rejected("Could not extract the downloaded update")
        }
        let extractedAppPath = "\(stagingDir)/Otterling.app"
        guard FileManager.default.fileExists(atPath: extractedAppPath) else {
            return .rejected("Downloaded update did not contain Otterling.app")
        }

        guard verifyCodeSignature(appPath: extractedAppPath, pinnedTeamID: pinnedTeamID) else {
            return .rejected(
                "Code signature verification failed, or the signing Team ID doesn't match the " +
                "pinned value -- refusing to install"
            )
        }

        guard swapIntoApplications(newAppPath: extractedAppPath) else {
            return .rejected("Verified update failed to install into \(FocusLockConstants.installedAppBundlePath)")
        }

        return .installedPendingRestart(manifest)
    }

    /// Called after a successful install: restarts the watchdog (a separate LaunchDaemon job, so
    /// it must be told explicitly) so it also picks up its freshly-installed binary, then exits --
    /// `KeepAlive=true` in this daemon's own plist relaunches it immediately, this time running the
    /// new `FocusLockHelperd` binary that was just swapped into place. Never returns.
    static func restartAfterInstall() -> Never {
        _ = run("/bin/launchctl", ["kickstart", "-k", "system/\(FocusLockConstants.watchdogBundleIdentifier)"])
        exit(0)
    }

    // MARK: - Manifest fetch

    private static func fetchManifest(host: String) -> UpdateManifest? {
        guard let url = URL(string: "https://\(host)\(FocusLockConstants.updateManifestPathSuffix)") else { return nil }
        let semaphore = DispatchSemaphore(value: 0)
        var body: Data?
        let task = URLSession.shared.dataTask(with: url) { data, _, _ in
            body = data
            semaphore.signal()
        }
        task.resume()
        _ = semaphore.wait(timeout: .now() + 15)
        guard let body else { return nil }
        return try? JSONDecoder().decode(UpdateManifest.self, from: body)
    }

    // MARK: - Download

    private static func downloadFile(urlString: String, toPath destinationPath: String) -> Bool {
        guard let url = URL(string: urlString) else { return false }
        let semaphore = DispatchSemaphore(value: 0)
        var succeeded = false
        let task = URLSession.shared.downloadTask(with: url) { tempURL, _, error in
            defer { semaphore.signal() }
            guard let tempURL, error == nil else { return }
            do {
                try? FileManager.default.removeItem(atPath: destinationPath)
                try FileManager.default.moveItem(at: tempURL, to: URL(fileURLWithPath: destinationPath))
                succeeded = true
            } catch {
                succeeded = false
            }
        }
        task.resume()
        _ = semaphore.wait(timeout: .now() + 120)
        return succeeded
    }

    // MARK: - Verification

    private static func sha256OfFile(atPath path: String) -> String? {
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func unzip(zipPath: String, into destinationDir: String) -> Bool {
        let (status, _) = runCapturingOutput("/usr/bin/unzip", ["-q", zipPath, "-d", destinationDir])
        return status == 0
    }

    /// Two checks, both required: a structural signature-validity check (`codesign --verify`,
    /// catches a corrupted/tampered/unsigned bundle) and the actual trust decision (parsed
    /// Team Identifier equals the pinned one -- catches a validly-signed bundle signed by the
    /// *wrong* party, which `--verify` alone would happily accept).
    private static func verifyCodeSignature(appPath: String, pinnedTeamID: String) -> Bool {
        let (verifyStatus, _) = runCapturingOutput("/usr/bin/codesign", ["--verify", "--deep", "--strict", appPath])
        guard verifyStatus == 0 else { return false }

        let (_, detailsOutput) = runCapturingOutput("/usr/bin/codesign", ["-dv", "--verbose=4", appPath])
        for line in detailsOutput.split(separator: "\n") where line.hasPrefix("TeamIdentifier=") {
            let teamID = line.dropFirst("TeamIdentifier=".count).trimmingCharacters(in: .whitespaces)
            return teamID == pinnedTeamID
        }
        return false
    }

    // MARK: - Install

    /// Atomic (same-volume rename, via `FileManager.moveItem`) swap: the previous install is
    /// backed up to `.prev` only for the duration of the swap itself, removed immediately after --
    /// a failed/partial download or verification (above) never reaches this point at all, so
    /// there's nothing to roll back to except "the install that was already running fine a moment
    /// ago," which the Guardian can always restore with `Scripts/build_app.sh` regardless.
    private static func swapIntoApplications(newAppPath: String) -> Bool {
        let installedPath = FocusLockConstants.installedAppBundlePath
        let backupPath = installedPath + ".prev"
        let fm = FileManager.default
        try? fm.removeItem(atPath: backupPath)

        do {
            if fm.fileExists(atPath: installedPath) {
                try fm.moveItem(at: URL(fileURLWithPath: installedPath), to: URL(fileURLWithPath: backupPath))
            }
            try fm.moveItem(at: URL(fileURLWithPath: newAppPath), to: URL(fileURLWithPath: installedPath))
        } catch {
            // Best-effort rollback if the second move failed after the first succeeded.
            if fm.fileExists(atPath: backupPath), !fm.fileExists(atPath: installedPath) {
                try? fm.moveItem(at: URL(fileURLWithPath: backupPath), to: URL(fileURLWithPath: installedPath))
            }
            return false
        }
        try? fm.removeItem(atPath: backupPath)
        return true
    }

    // MARK: - Process helpers

    @discardableResult
    private static func run(_ path: String, _ args: [String]) -> Int32 {
        runCapturingOutput(path, args).status
    }

    private static func runCapturingOutput(_ path: String, _ args: [String]) -> (status: Int32, output: String) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        guard (try? process.run()) != nil else { return (-1, "failed to launch \(path)") }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return (process.terminationStatus, String(data: data, encoding: .utf8) ?? "")
    }
}
