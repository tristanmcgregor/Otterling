import Foundation

/// Shared identifiers so the app, daemon, and CLI agree on bundle IDs, the XPC Mach service
/// name, and where root-owned state lives on disk.
public enum FocusLockConstants {
    public static let appBundleIdentifier = "app.otterling"
    public static let helperBundleIdentifier = "app.otterling.helperd"
    public static let machServiceName = helperBundleIdentifier

    public static let stateDirectory = "/Library/Application Support/FocusLock"
    public static let stateFilePath = "\(stateDirectory)/state.json"
    /// Cache file for the downloaded adult-domain hosts lists (see `AdultBlocklistManager`).
    public static let adultBlocklistCachePath = "\(stateDirectory)/adult_domains.txt"

    /// pf anchor the daemon loads its `block drop` rules into.
    public static let pfAnchorName = "app.otterling"
    public static let pfAnchorFilePath = "/Library/Application Support/FocusLock/pf.anchor"

    /// Marker comment so the daemon can find/replace only the lines it owns in /etc/hosts.
    public static let hostsMarkerBegin = "# Otterling BEGIN - do not edit, managed by FocusLockHelperd"
    public static let hostsMarkerEnd = "# Otterling END"

    /// Hard ceiling on how many domains the daemon will ever write into /etc/hosts. This is a
    /// SAFETY limit, not a policy one: an oversized /etc/hosts cripples mDNSResponder and takes the
    /// machine fully offline -- the downloaded adult blocklists grew to ~1M domains, which produced
    /// a ~4,000,000-line /etc/hosts and blocked all internet. /etc/hosts is not a bulk-blocklist
    /// mechanism; comprehensive category blocking is the cloud filter's job. The Guardian's manual
    /// domains are always applied first; the downloaded list only fills whatever room remains under
    /// this cap. 15k domains ≈ 60k hosts lines, comfortably within what the resolver handles well.
    public static let maxHostsBlocklistDomains = 15_000

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

    // MARK: - mitmproxy content-filter proxy (optional, opt-in)

    /// The filter-server's mitmproxy HTTP CONNECT proxy (see filter-server/docker-compose.yml's
    /// `mitmproxy` service). When `ProxyEnforcer` is enabled, the daemon points every network
    /// service's system HTTP/HTTPS proxy here so the Mac's browser traffic is content-filtered the
    /// same way the phone's is -- and reports trigger words seen on blocked pages. Defaults share the
    /// cloud-filter host (one family server). Port matches `PROXY_PORT` (8090) in the compose file.
    public static let defaultProxyPort = 8090
    /// Proxy auth username, matching `PROXY_USER` in filter-server/.env (mitmproxy runs with
    /// `--proxyauth`). The password is NOT baked in -- it's read from `proxyPasswordPath`, written
    /// by `Scripts/setup_mac_proxy.command`. If that file is missing/empty, `ProxyEnforcer` refuses
    /// to set the proxy at all (fail OPEN -- an authenticated proxy set with no/wrong password would
    /// 407 every request and take the machine offline, exactly what we must never do).
    public static let defaultProxyUser = "otterling"
    /// Root-only (0600) file holding the proxy password, written next to state.json by
    /// `Scripts/setup_mac_proxy.command`. Absent by default -> proxy enforcement stays inert.
    public static let proxyPasswordPath = "\(stateDirectory)/proxy_password"

    /// How long an authorized protection-reducing action waits before `EnforcementLoop` applies it.
    /// 24h is chosen to outlast an impulse rather than to be merely annoying -- the cooldown is the
    /// part of the design that still works when the person it's slowing down holds admin, so it has
    /// to be long enough that "wait it out" isn't a comfortable alternative to not doing it.
    public static let defaultCooldownHours: Double = 24

    /// Ceiling on `cooldownHours`, so a fat-fingered "10000" can't wedge the install into a state
    /// where nothing can ever be removed through the supported path.
    public static let maximumCooldownHours: Double = 24 * 30

    /// PayloadIdentifier of the lock profile `Scripts/install_lock_profile.py` installs (matches
    /// `PROFILE_IDENTIFIER` in `filter-server/lockprofile_service.py`). A tamper *tripwire*, not a
    /// removal lock -- see GUARDIAN_SETUP.md §6. `LockProfileGuard` polls for this identifier's
    /// presence via `profiles show -type configuration`.
    public static let lockProfileIdentifier = "app.otterling.lockprofile"

    /// Root-only files `install_lock_profile.py` writes next to `state.json`, holding the
    /// filter-server host + bearer token `TamperReporter` needs to POST to `/alerts/tamper`.
    /// When present they take precedence over the baked-in defaults below.
    public static let lockProfileTokenPath = "\(stateDirectory)/lockprofile_token"
    public static let lockProfileHostPath = "\(stateDirectory)/lockprofile_host"

    /// Baked-in fallbacks so on-device tamper reporting (daemon-unloaded, watchdog recovery, lock-
    /// profile removed) reaches the filter-server -- and from there ntfy + the phone's SMS relay --
    /// out of the box, before/without running `install_lock_profile.py`. The provisioned files
    /// above override these when they exist.
    ///
    /// Embedding the token means it ships inside the app bundle and is extractable by anyone with
    /// the binary. That's an accepted trade here: `/alerts/tamper` is append-only ingestion for a
    /// personal accountability deployment, so the worst a leaked token buys is posting spurious
    /// alerts (noise the Guardian sees) or reading the alert feed -- not turning off any protection.
    /// Rotate it by regenerating `LOCKPROFILE_TOKEN` server-side and updating this constant.
    public static let defaultLockProfileHost = defaultCloudFilterHost
    public static let defaultLockProfileToken = "22ff3ed0a6b843633a6499911abb7378239e6e9e6cbd97d56e465b39d0dbdc9b"

    /// Fixed install location `Scripts/build_app.sh` assembles the app bundle at -- both
    /// LaunchDaemon plists live under here, embedded (see `Contents/Library/LaunchDaemons` in that
    /// script) so `SMAppService`/`launchctl bootstrap` can find them.
    public static let installedAppBundlePath = "/Applications/Otterling.app"
    public static let helperLaunchDaemonPlistPath =
        "\(installedAppBundlePath)/Contents/Library/LaunchDaemons/\(helperBundleIdentifier).plist"

    /// FocusLockWatchdog: an independent LaunchDaemon whose only job is re-bootstrapping
    /// FocusLockHelperd if it's ever unloaded outside its own XPC surface (e.g. `sudo launchctl
    /// bootout`) -- detects and reports, doesn't prevent; see GUARDIAN_SETUP.md §5.
    public static let watchdogBundleIdentifier = "app.otterling.watchdog"
    public static let watchdogLaunchDaemonPlistPath =
        "\(installedAppBundlePath)/Contents/Library/LaunchDaemons/\(watchdogBundleIdentifier).plist"

    /// FocusLockScanner: a per-user LaunchAgent (not a daemon -- the Accessibility API only works
    /// inside a GUI login session, and TCC Accessibility trust is granted per-user) that walks the
    /// frontmost browser's accessibility tree and reports on-screen trigger words via
    /// `TamperReporter` -- the macOS equivalent of the phone's `FocusGuardAccessibilityService`.
    /// Registered with `SMAppService.agent`, so its plist lives under Contents/Library/LaunchAgents.
    public static let scannerBundleIdentifier = "app.otterling.scanner"
    public static let scannerLaunchAgentPlistPath =
        "\(installedAppBundlePath)/Contents/Library/LaunchAgents/\(scannerBundleIdentifier).plist"

    /// How often `FocusLockScanner` re-walks the frontmost window. Matches the phone's
    /// `TRIGGER_SCAN_DEBOUNCE_MS` (2s) -- fast enough to catch a page before it's scrolled away,
    /// cheap enough not to churn CPU on a mostly-static screen.
    public static let scannerScanInterval: Double = 2

    /// Bundle identifiers `FocusLockScanner` treats as browsers -- the only apps it scans, matching
    /// the phone's browser/YouTube-only gating (`FocusGuardAccessibilityService`). Scanning every
    /// app would both cost more and misfire on a listed word appearing in an editor/doc/chat.
    public static let browserBundleIdentifiers: Set<String> = [
        "com.apple.Safari",
        "com.apple.SafariTechnologyPreview",
        "com.google.Chrome",
        "com.google.Chrome.canary",
        "org.chromium.Chromium",
        "org.mozilla.firefox",
        "org.mozilla.firefoxdeveloperedition",
        "com.microsoft.edgemac",
        "com.brave.Browser",
        "com.operasoftware.Opera",
        "com.vivaldi.Vivaldi",
        "company.thebrowser.Browser",   // Arc
        "com.apple.WebKit.WebContent",  // Safari renderer process, seen as frontmost in some setups
    ]

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
    /// before relying on auto-update.
    ///
    /// IMPORTANT: use the value from `codesign -dv --verbose=4 <app>`'s own `TeamIdentifier=` line,
    /// not the parenthesized suffix in the certificate's display name (`security find-identity`) --
    /// on at least one "Apple Development" personal-team certificate observed here, those two
    /// differed (identity showed "(C438Q9HAHP)" in its Common Name, but the actual embedded
    /// `TeamIdentifier` on every build it produces was "D4XJKWV7GY"). Trusting the display name
    /// instead of `codesign`'s own output silently made every build fail its own update check.
    public static let pinnedUpdateTeamID = "D4XJKWV7GY"

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
