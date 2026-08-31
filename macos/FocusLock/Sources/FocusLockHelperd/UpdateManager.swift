import CryptoKit
import Foundation
import FocusLockShared

/// Fetches, verifies, and installs Otterling updates -- macOS counterpart to the Android app's
/// `ApprovedUpdateManager`, same trust chain, in the same order, plus one macOS-only step:
///   1. The manifest names a version/downloadUrl/sha256/gitSha, and must carry a
///      `reviewAttestation` signature (checked against `pinnedReviewAttestationPublicKey`) --
///      see that constant's doc comment. Unlike Android, where the release host holds the actual
///      APK signing keystore (so "AI-reviewed" and "able to produce a trusted binary" are the same
///      gate), this host can't hold the Apple signing identity, so this is a second signature that
///      closes that gap independently. Checked first, before any network I/O, since a manifest
///      that fails this is never worth downloading anything for.
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

        let pinnedAttestationKey = FocusLockConstants.pinnedReviewAttestationPublicKey
        guard !pinnedAttestationKey.isEmpty else {
            return .rejected(
                "This build has no pinned review-attestation public key " +
                "(FocusLockConstants.pinnedReviewAttestationPublicKey) -- refusing to install any " +
                "update. See that constant's doc comment."
            )
        }
        guard verifyReviewAttestation(manifest, pinnedPublicKeyBase64: pinnedAttestationKey) else {
            return .rejected(
                "Review attestation signature is missing or invalid -- this update cannot be " +
                "verified as having passed AI review, refusing to install"
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
    /// it must be told explicitly) so it also picks up its freshly-installed binary, then forces
    /// THIS job to restart too, before finally exiting itself. Never returns.
    ///
    /// Explicitly `kickstart -k`s this job's own label rather than relying only on `exit(0)` +
    /// `KeepAlive=true` to get relaunched passively -- confirmed live 2026-08-30: after a real
    /// swapped-in update, a daemon that had been running for hours never actually restarted despite
    /// (apparently) reaching this function repeatedly across several successive automatic-update
    /// cycles, staying on old code indefinitely while the on-disk binary moved on to newer builds --
    /// `active count` in `launchctl print` never incremented. `kickstart -k` forces an immediate
    /// restart unconditionally (bypassing whatever launchd-side throttling or stale cached job
    /// state a passive self-exit might be silently subject to), the same command a Guardian running
    /// this by hand would reach for -- so this makes the daemon do to itself, deterministically,
    /// exactly what manual recovery already required, instead of hoping KeepAlive notices in time.
    /// The trailing `exit(0)` is now just a safety net for the (expected) case where the kickstart
    /// below kills this process before it gets there.
    ///
    /// Also kickstarts each job's `.direct` label (see `DirectLabelBootstrap`) -- confirmed live
    /// 2026-08-31: on a Mac whose real `app.otterling.watchdog` registration was stuck in
    /// Background Task Management, `FocusLockWatchdog` had been running under the `.direct` label
    /// fallback for hours. This function only ever kickstarted the real label, which no-ops
    /// silently on a label nothing is registered under (`runSilently` discards the exit status) --
    /// so the `.direct`-label watchdog kept running against the OLD (just-replaced-on-disk)
    /// binary indefinitely. Its now-unresolvable code signature then made `XPCPeerValidator`
    /// reject it as an "unrecognized process" the next time it polled the freshly-restarted
    /// helperd -- a false-positive tamper report, not real tampering. Kickstarting both labels
    /// restarts whichever one is actually active and is a harmless no-op on the other.
    static func restartAfterInstall() -> Never {
        for label in [FocusLockConstants.watchdogBundleIdentifier, "\(FocusLockConstants.watchdogBundleIdentifier).direct"] {
            ProcessRunner.runSilently("/bin/launchctl", ["kickstart", "-k", "system/\(label)"])
        }
        for label in [FocusLockConstants.helperBundleIdentifier, "\(FocusLockConstants.helperBundleIdentifier).direct"] {
            ProcessRunner.runSilently("/bin/launchctl", ["kickstart", "-k", "system/\(label)"])
        }
        exit(0)
    }

    // MARK: - Manifest fetch

    private static func fetchManifest(host: String) -> UpdateManifest? {
        guard let url = URL(string: "https://\(host)\(FocusLockConstants.updateManifestPathSuffix)") else { return nil }
        // Caddy serves this file with no Cache-Control/Expires header, so URLSession.shared's
        // persistent disk cache applies HTTP heuristic freshness and can silently keep serving a
        // stale manifest body (observed: a manifest from days earlier, missing fields a newer
        // schema requires) without ever re-hitting the network. A version/signature check has to
        // see the real current file every time, so bypass the cache entirely rather than trust it.
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        let semaphore = DispatchSemaphore(value: 0)
        var body: Data?
        let task = URLSession.shared.dataTask(with: request) { data, _, _ in
            body = data
            semaphore.signal()
        }
        task.resume()
        if semaphore.wait(timeout: .now() + 15) == .timedOut {
            // Cancel rather than let the task keep running and its completion handler fire (and
            // write into `body`) after this function has already returned -- an unbounded-lifetime
            // connection otherwise leaks on every hung update host, checked hourly.
            task.cancel()
            return nil
        }
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
        if semaphore.wait(timeout: .now() + 120) == .timedOut {
            task.cancel()
            return false
        }
        return succeeded
    }

    // MARK: - Verification

    private static func sha256OfFile(atPath path: String) -> String? {
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Extracts only after confirming no entry would land outside `destinationDir`.
    ///
    /// Reaching this point already requires a manifest signed by the review host's Ed25519 key and
    /// a matching SHA-256, so a hostile archive is not the expected case -- but "an archive we
    /// already trust" is exactly the assumption a zip-slip check is cheap insurance against, and
    /// this runs as root with `/Applications` as its neighbour. `unzip -j` is not an option (it
    /// flattens the directory structure, which destroys the .app bundle), so the listing is
    /// validated first instead.
    private static func unzip(zipPath: String, into destinationDir: String) -> Bool {
        let listing = ProcessRunner.run("/usr/bin/unzip", ["-Z", "-1", zipPath])
        guard listing.status == 0 else { return false }
        for entry in listing.output.split(separator: "\n").map(String.init) {
            let path = entry.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !path.isEmpty else { continue }
            if path.hasPrefix("/") || path.hasPrefix("../") || path.contains("/../") || path == ".." {
                FileHandle.standardError.write(
                    "[update] refusing archive: entry '\(path)' would extract outside the staging directory\n".data(using: .utf8)!
                )
                return false
            }
        }
        return ProcessRunner.run("/usr/bin/unzip", ["-q", zipPath, "-d", destinationDir]).status == 0
    }

    /// Verifies `manifest.reviewAttestation` (base64 Ed25519 signature, from `sudo
    /// otterling-attest-macos` on the AI-review host) against `pinnedPublicKeyBase64`, over the
    /// exact same `"versionCode|versionName|sha256|gitSha"` payload the host signed -- must match
    /// byte-for-byte or the signature won't verify. Any malformed base64/key/signature fails
    /// closed (returns `false`), same as an actual verification mismatch.
    private static func verifyReviewAttestation(_ manifest: UpdateManifest, pinnedPublicKeyBase64: String) -> Bool {
        guard let pinnedKeyData = Data(base64Encoded: pinnedPublicKeyBase64),
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: pinnedKeyData),
              let signature = Data(base64Encoded: manifest.reviewAttestation) else {
            return false
        }
        let payload = "\(manifest.versionCode)|\(manifest.versionName)|\(manifest.sha256)|\(manifest.gitSha)"
        guard let payloadData = payload.data(using: .utf8) else { return false }
        return publicKey.isValidSignature(signature, for: payloadData)
    }

    /// Two checks, both required: a structural signature-validity check (`codesign --verify`,
    /// catches a corrupted/tampered/unsigned bundle) and the actual trust decision (parsed
    /// Team Identifier equals the pinned one -- catches a validly-signed bundle signed by the
    /// *wrong* party, which `--verify` alone would happily accept).
    private static func verifyCodeSignature(appPath: String, pinnedTeamID: String) -> Bool {
        guard ProcessRunner.run("/usr/bin/codesign", ["--verify", "--deep", "--strict", appPath]).status == 0 else {
            return false
        }

        let detailsOutput = ProcessRunner.run("/usr/bin/codesign", ["-dv", "--verbose=4", appPath]).output
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
}
