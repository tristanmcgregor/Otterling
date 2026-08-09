import Foundation

/// Shared identifiers so the app, daemon, and CLI agree on bundle IDs, the XPC Mach service
/// name, and where root-owned state lives on disk.
public enum FocusLockConstants {
    public static let appBundleIdentifier = "au.com.tbmcgregor.bwparker.focuslock"
    public static let helperBundleIdentifier = "au.com.tbmcgregor.bwparker.focuslock.helperd"
    public static let machServiceName = helperBundleIdentifier

    public static let stateDirectory = "/Library/Application Support/FocusLock"
    public static let stateFilePath = "\(stateDirectory)/state.json"
    /// Cache file for the downloaded adult-domain hosts lists (see `AdultBlocklistManager`).
    public static let adultBlocklistCachePath = "\(stateDirectory)/adult_domains.txt"

    /// pf anchor the daemon loads its `block drop` rules into.
    public static let pfAnchorName = "au.com.tbmcgregor.focuslock"
    public static let pfAnchorFilePath = "/Library/Application Support/FocusLock/pf.anchor"

    /// Marker comment so the daemon can find/replace only the lines it owns in /etc/hosts.
    public static let hostsMarkerBegin = "# Otterling BEGIN - do not edit, managed by FocusLockHelperd"
    public static let hostsMarkerEnd = "# Otterling END"

    /// Marker comments so the daemon can find/replace only the anchor reference it owns in
    /// /etc/pf.conf, without touching Apple's or any other tool's rules.
    public static let pfConfMarkerBegin = "# Otterling BEGIN - do not edit, managed by FocusLockHelperd"
    public static let pfConfMarkerEnd = "# Otterling END"

    /// Previous brand's markers -- recognized alongside the current ones so an upgrade from an
    /// older (FocusLock-branded) build cleans up its own managed blocks instead of leaving them
    /// as permanently-orphaned entries in /etc/hosts / /etc/pf.conf that neither the old nor new
    /// marker text matches anymore.
    public static let legacyHostsMarkerBegin = "# FocusLock BEGIN - do not edit, managed by FocusLockHelperd"
    public static let legacyHostsMarkerEnd = "# FocusLock END"
    public static let legacyPFConfMarkerBegin = "# FocusLock BEGIN - do not edit, managed by FocusLockHelperd"

    /// Default cloud content-filter DNS host (a Canopy-style AdGuard Home deployment -- see
    /// filter-server/README.md at the repo root), mirroring the Android app's `CloudFilterSettings`.
    public static let defaultCloudFilterHost = "vpn.bartholomew.help"

    /// PayloadIdentifier of the lock profile `Scripts/install_lock_profile.py` installs (matches
    /// `PROFILE_IDENTIFIER` in `filter-server/lockprofile_service.py`). A tamper *tripwire*, not a
    /// removal lock -- see GUARDIAN_SETUP.md §6. `LockProfileGuard` polls for this identifier's
    /// presence via `profiles show -type configuration`.
    public static let lockProfileIdentifier = "au.com.tbmcgregor.bwparker.focuslock.lockprofile"

    /// Root-only files `install_lock_profile.py` writes next to `state.json`, holding the
    /// filter-server host + bearer token `TamperReporter` needs to POST to `/alerts/tamper`.
    public static let lockProfileTokenPath = "\(stateDirectory)/lockprofile_token"
    public static let lockProfileHostPath = "\(stateDirectory)/lockprofile_host"

    /// Fixed install location `Scripts/build_app.sh` assembles the app bundle at -- both
    /// LaunchDaemon plists live under here, embedded (see `Contents/Library/LaunchDaemons` in that
    /// script) so `SMAppService`/`launchctl bootstrap` can find them.
    public static let installedAppBundlePath = "/Applications/Otterling.app"
    public static let helperLaunchDaemonPlistPath =
        "\(installedAppBundlePath)/Contents/Library/LaunchDaemons/\(helperBundleIdentifier).plist"

    /// FocusLockWatchdog: an independent LaunchDaemon whose only job is re-bootstrapping
    /// FocusLockHelperd if it's ever unloaded outside its own XPC surface (e.g. `sudo launchctl
    /// bootout`) -- detects and reports, doesn't prevent; see GUARDIAN_SETUP.md §5.
    public static let watchdogBundleIdentifier = "au.com.tbmcgregor.bwparker.focuslock.watchdog"
    public static let watchdogLaunchDaemonPlistPath =
        "\(installedAppBundlePath)/Contents/Library/LaunchDaemons/\(watchdogBundleIdentifier).plist"

    /// This build's version -- bump by hand each release, matching `CFBundleShortVersionString`
    /// in `Scripts/build_app.sh` (kept in sync manually, not code-generated -- there's no
    /// Gradle-style single-source-of-truth build system here, and duplicating one integer by hand
    /// across two files beats adding build-time codegen for it). `UpdateManager` compares this
    /// against a manifest's `versionCode` the same way Android's `BuildConfig.VERSION_CODE` does.
    public static let appVersionCode = 1

    /// The Team Identifier (from `codesign -dv`, e.g. "ABCDE12345") that a downloaded update's
    /// `.app` bundle must be signed by, checked *in addition to* SHA-256 -- this is the actual
    /// root of trust, mirroring Android's `BuildConfig.RELEASE_CERT_SHA256`: a compromised update
    /// host could publish a resigned bundle with a matching self-authored manifest (passing the
    /// SHA-256 check), but it can't forge this. **Empty by default, and `UpdateManager` refuses to
    /// install anything while it's empty** -- fail closed, same stance Android takes for a build
    /// with no `RELEASE_CERT_SHA256`. Fill in your own Apple Developer Team ID here and rebuild
    /// before relying on auto-update; find it with `security find-identity -v -p codesigning` (the
    /// parenthesized suffix after your certificate name) or in the Apple Developer portal.
    public static let pinnedUpdateTeamID = "C438Q9HAHP"

    /// Where `UpdateManager` looks for the manifest -- see `filter-server/updates/README.md` and
    /// `macos/FocusLock/RELEASE.md` for how it gets published. Uses the same host as the cloud
    /// content filter/lock-profile services (one family server), read from persisted state
    /// (`cloudFilterHost`) rather than hardcoded, so pointing the app at a different host also
    /// repoints updates.
    public static let updateManifestPathSuffix = "/updates/macos-manifest.json"

    /// Staging path `UpdateManager` downloads/verifies an update into before the atomic swap into
    /// `installedAppBundlePath` -- kept outside `/Applications` so a failed/partial download or
    /// verification never touches the live install.
    public static let updateStagingDirectory = "\(stateDirectory)/update-staging"
}
