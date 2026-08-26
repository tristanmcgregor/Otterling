import Foundation

/// Mirrors Android's `UpdateManifest` (`updates/ApprovedUpdateManager.kt`) field-for-field where
/// it makes sense, published at `<cloudFilterHost>/updates/macos-manifest.json` -- see
/// `filter-server/updates/README.md` and `macos/FocusLock/RELEASE.md` for how it gets there (a
/// manual/local publish step for now: the existing AI-gated release host is Linux-only and can't
/// build or code-sign a macOS `.app`, so there's no automated CI path yet).
public struct UpdateManifest: Codable, Sendable {
    public let versionCode: Int
    public let versionName: String
    public let downloadUrl: String
    public let sha256: String
    /// Team Identifier the downloaded bundle must be signed by -- cross-checked against the
    /// locally pinned `FocusLockConstants.pinnedUpdateTeamID`, not trusted from the manifest alone
    /// (a compromised host could just publish whatever Team ID matches its own resigned bundle).
    /// Present here mainly so `UpdateStatus`/the GUI can show it, not as the actual trust anchor.
    public let codesignTeamId: String
    /// Git SHA this build corresponds to. Unlike Android's `gitSha` (informational-only, never
    /// read by the client -- see `ApprovedUpdateManager.kt`), this one IS verified here: it's part
    /// of the payload `reviewAttestation` signs over, so it can't be swapped for a different SHA
    /// without invalidating the signature.
    public let gitSha: String
    /// Base64 Ed25519 signature (from `sudo otterling-attest-macos` on the AI-review host) over
    /// `"\(versionCode)|\(versionName)|\(sha256)|\(gitSha)"`, verified against
    /// `FocusLockConstants.pinnedReviewAttestationPublicKey` -- see that constant's doc comment for
    /// why this exists. This, not `codesignTeamId`, is the field that ties an update to having
    /// actually passed AI review.
    public let reviewAttestation: String

    /// No defaults on `gitSha`/`reviewAttestation` -- both required, deliberately. Since this
    /// struct has no custom `init(from:)`, a manifest JSON missing either field simply fails to
    /// decode at all (surfaces as `.error(...)` from `checkForUpdate`, never `.updateAvailable`),
    /// which is fail-closed for free rather than something `UpdateManager` has to remember to check.
    public init(
        versionCode: Int, versionName: String, downloadUrl: String, sha256: String,
        codesignTeamId: String, gitSha: String, reviewAttestation: String
    ) {
        self.versionCode = versionCode
        self.versionName = versionName
        self.downloadUrl = downloadUrl
        self.sha256 = sha256
        self.codesignTeamId = codesignTeamId
        self.gitSha = gitSha
        self.reviewAttestation = reviewAttestation
    }
}

/// Result of `UpdateManager.checkForUpdate()` -- crosses XPC the same JSON-`Data` way every other
/// payload in this protocol does (see `FocusLockCodec`).
public enum UpdateCheckStatus: Codable, Sendable {
    case upToDate
    case updateAvailable(UpdateManifest)
    case error(String)
}

/// Result of `UpdateManager.downloadVerifyAndInstall()`.
public enum UpdateInstallResult: Codable, Sendable {
    case installedPendingRestart(UpdateManifest)
    case rejected(String)
}
