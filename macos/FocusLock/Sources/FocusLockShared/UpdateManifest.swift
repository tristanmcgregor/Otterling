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

    public init(versionCode: Int, versionName: String, downloadUrl: String, sha256: String, codesignTeamId: String) {
        self.versionCode = versionCode
        self.versionName = versionName
        self.downloadUrl = downloadUrl
        self.sha256 = sha256
        self.codesignTeamId = codesignTeamId
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
