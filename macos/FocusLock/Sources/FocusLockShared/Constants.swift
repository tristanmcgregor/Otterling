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

    /// Creates `stateDirectory` if missing, so processes that write into it standalone (e.g.
    /// `TamperReporter` from the watchdog or scanner CLI, which may run before the main daemon
    /// ever has) don't fail. 0711, not 0700: this directory also holds `proxyCACertPath`, a
    /// deliberately world-readable file the console user's own CLI tools must open directly by
    /// path -- `--x` for group/other grants exactly that traverse-by-known-name without directory
    /// listing. Individual files still carry their own restrictive mode.
    public static func ensureStateDirectoryExists() {
        guard !FileManager.default.fileExists(atPath: stateDirectory) else { return }
        try? FileManager.default.createDirectory(
            atPath: stateDirectory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o711]
        )
    }

    /// pf anchor the daemon loads its `block drop` rules into.
    public static let pfAnchorName = "app.otterling"
    public static let pfAnchorFilePath = "/Library/Application Support/FocusLock/pf.anchor"

    /// Written by `XPCService.killSwitch` just before it disables everything: a snapshot of the
    /// DNS/proxy settings as they stood right before the kill switch fired, so
    /// `restoreFromKillSwitch` can put them back exactly as they were rather than guessing a
    /// default. Deleted once restore succeeds. Living outside `state.json` on purpose -- the kill
    /// switch's whole point is working even if state.json's own fields can't be trusted mid-crisis.
    public static let killSwitchSnapshotPath = "\(stateDirectory)/killswitch_snapshot.json"

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

    /// The filter-server's LAN address (see `~/.ssh/config`'s `192.168.0.254` entries and
    /// AdGuardHome's own `vpn.bartholomew.help -> 192.168.0.254` rewrite rule). The lock profile's
    /// `com.apple.dnsSettings.managed` payload points the Mac's real DNS resolution at public
    /// Cloudflare DoH (see `lockprofile_service.py`'s `FAMILY_DOH_URL` comment) -- which, unlike our
    /// own AdGuardHome, has no idea this host is on the LAN and answers with its public WAN IP
    /// instead. `DNSEnforcer`/`ProxyEnforcer` resolving the hostname the normal way inherits that
    /// same public answer, which makes every DNS lookup and every proxied web request hairpin out
    /// through the home router and back in -- slow, and unreliable under Chrome's heavier concurrent
    /// connection load. Both enforcers check this literal IP for reachability first and use it
    /// directly (skipping hostname resolution entirely) when it answers, falling back to normal
    /// hostname resolution when away from home.
    public static let homeLANHost = "192.168.0.254"

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

    /// World-readable (0644) copy of the mitmproxy CA cert, written next to state.json by
    /// `Scripts/setup_mac_proxy.command`. `security add-trusted-cert` puts the same cert in the
    /// System keychain, which is enough for GUI apps/browsers on macOS (they validate through
    /// Security.framework) but NOT for Node/Python/Go CLI tools -- `curl`, `npm`, `pip`, and
    /// critically the `claude` CLI itself all carry their own bundled root store (or, for Node,
    /// read `NODE_EXTRA_CA_CERTS` instead of the Keychain) and have no idea this CA exists. Without
    /// this file and `ShellProxyEnvManager` pointing those tools at it, turning on
    /// `enable-proxy --force` makes every such CLI tool fail TLS verification against mitmproxy's
    /// leaf certs -- indistinguishable, from the user's seat, from "network blocked" -- even though
    /// browsers on the same Mac work fine. World-readable is required (not root-only, unlike
    /// `proxyPasswordPath`): the console user's own CLI tools must be able to read it, and it's a
    /// public certificate, not a secret (see filter-server/ca/README.md).
    public static let proxyCACertPath = "\(stateDirectory)/proxy_ca.pem"

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

    /// Root-only file holding the Anthropic API key `AIAssistantClient` exports into its local,
    /// non-interactive `claude` CLI session -- API-key auth, not the household's own interactive
    /// Claude Code subscription login, since a root LaunchDaemon has no login session for `claude`
    /// to read OAuth credentials from (`--bare` mode deliberately refuses to try). Provision by
    /// writing this file (0600, root:wheel) with nothing but the key. Absent -> the AI Assistant
    /// reports itself unreachable rather than silently doing nothing.
    public static let anthropicApiKeyPath = "\(stateDirectory)/anthropic_api_key"
    /// Optional absolute path to the `claude` binary, for a Mac where it isn't in one of
    /// `AIAssistantClient`'s auto-probed install locations (`/opt/homebrew/bin`, `/usr/local/bin`
    /// -- deliberately never anywhere under a console user's home directory; see that type's
    /// `resolveClaudeExecutable` doc comment for why). The target this points at is executed by a
    /// root daemon, so it must not be writable by the account being filtered either -- this file
    /// relocates *where* the daemon looks, not the trust requirement on what it finds there.
    public static let claudeCliPathOverridePath = "\(stateDirectory)/claude_cli_path"

    /// Per-type last-sent timestamps `TamperReporter` uses to rate-limit `/alerts/tamper` POSTs.
    /// Lives here (not in-memory) because callers span separate processes -- the daemon, the
    /// watchdog LaunchDaemon, and the scanner CLI -- that don't share memory but do all run as
    /// root, so a shared root-owned file is the only way to throttle a flapping event (e.g. DNS
    /// floor disabled/re-enabled) across all of them.
    public static let tamperReportStatePath = "\(stateDirectory)/tamper_report_state.json"

    /// Baked-in fallbacks so on-device tamper reporting (daemon-unloaded, watchdog recovery, lock-
    /// profile removed) reaches the filter-server -- and from there ntfy + the phone's SMS relay --
    /// out of the box, before/without running `install_lock_profile.py`. The provisioned files
    /// above override these when they exist.
    ///
    /// Embedding the token means it ships inside the app bundle and is extractable by anyone with
    /// the binary. That was originally an accepted trade because `/alerts/tamper` is append-only
    /// ingestion, so the worst a leaked token bought was posting spurious alerts or reading the
    /// alert feed -- **not turning off any protection**. That premise changed once
    /// `DashboardConfigSync` shipped: possession of this same token is now also sufficient to
    /// apply a Mac protection removal (blocked-app unblock, DNS enforcement off) immediately
    /// through `DashboardConfigSync.reconcile`'s dashboard-authorized path -- deliberately, per
    /// this project's plan doc, since requiring local confirmation for every remote removal would
    /// defeat the point of a remote dashboard, but a leaked/extracted token is no longer
    /// harmless-by-design the way this comment used to claim. Rotate it by regenerating
    /// `LOCKPROFILE_TOKEN` server-side and updating this constant.
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

    /// Default/floor for how often `FocusLockScanner`'s `ScreenshotMonitor` captures the frontmost
    /// app and uploads it to `/screenshot-classify` -- the Mac equivalent of the phone's
    /// `DEFAULT_VISUAL_FILTER_INTERVAL_SECONDS`/`MIN_VISUAL_FILTER_INTERVAL_SECONDS`
    /// (`FocusGuardAccessibilityService`). `DashboardVisualFilterSettings` polls the guardian
    /// dashboard's per-device `visualFilterIntervalSeconds` independently and falls back to this
    /// default/floor when unconfigured or unreachable -- unlike `scannerScanInterval` above (which
    /// genuinely has no dashboard-tunable equivalent), this one does.
    public static let screenshotScanInterval: Double = 30
    public static let screenshotMinScanInterval: Double = 15

    /// Matches the phone's `SCREENSHOT_MAX_DIMENSION`/`SCREENSHOT_JPEG_QUALITY` (see
    /// `FocusGuardAccessibilityService.kt`) -- plenty of resolution for a vision/ONNX classifier to
    /// judge "is this NSFW", drastically cuts upload size vs. full Retina display resolution.
    public static let screenshotMaxDimension: Double = 720
    /// `NSBitmapImageRep`'s `.compressionFactor` is 0...1 (opposite convention from Android's
    /// 0...100 JPEG quality) -- 0.8 here is the same "80%" the phone uses.
    public static let screenshotJPEGCompressionFactor: Double = 0.8

    /// Own bundle IDs + the lock screen -- screenshotting our own Settings UI or a locked screen is
    /// wasted uploads and a mild privacy smell, matching the phone's `screenshotSkipPackages` (own
    /// package + launcher/systemui/keyguard).
    public static let screenshotSkipBundleIdentifiers: Set<String> = [
        "app.otterling",
        "app.otterling.scanner",
        "app.otterling.watchdog",
        "com.apple.loginwindow",
    ]

    /// Matches the phone's `DEFAULT_NSFW_BLOCK_MILLIS`/`MIN_NSFW_BLOCK_MILLIS` -- how long
    /// `ScreenshotMonitor` keeps force-quitting an app after an "nsfw" verdict when the server
    /// response doesn't include its own `blockUntilMillis` (or as a floor when it does, to survive
    /// phone/Mac clock skew).
    public static let defaultNsfwBlockSeconds: Double = 15 * 60
    public static let minNsfwBlockSeconds: Double = 60
    /// Matches the phone's `NSFW_BLOCK_TRIGGER_COUNT` -- requires this many consecutive "nsfw"
    /// verdicts in a row (not just one) before `ScreenshotMonitor` actually force-quits the app,
    /// since a single flagged screenshot is too easily a false positive (e.g. one frame of a
    /// normal video).
    public static let nsfwBlockTriggerCount: Int = 3

    /// This build's version -- bump by hand each release, matching `CFBundleShortVersionString`
    /// in `Scripts/build_app.sh` (kept in sync manually, not code-generated -- there's no
    /// Gradle-style single-source-of-truth build system here, and duplicating one integer by hand
    /// across two files beats adding build-time codegen for it). `UpdateManager` compares this
    /// against a manifest's `versionCode` the same way Android's `BuildConfig.VERSION_CODE` does.
    public static let appVersionCode = 26

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

    /// Raw 32-byte Ed25519 public key (base64), matching the private key kept ONLY at
    /// `/var/lib/otterling/ci/secrets/macos_review_attestation_ed25519` on the AI-review host. That
    /// host's `attest_macos_release.sh` (`sudo otterling-attest-macos`) refuses to sign anything
    /// unless `last_published_sha` -- written only by `release.sh` after a cumulative AI
    /// `VERDICT: PASS` -- names the git SHA being attested to. So a valid signature here means
    /// "this exact (versionCode, versionName, sha256, gitSha) tuple was attested to by the one host
    /// that only ever attests to AI-reviewed commits", independent of who ran
    /// `publish_release.sh` or what Apple signing identity they used.
    ///
    /// This exists because, unlike Android (where the release host holds the actual APK signing
    /// keystore, so passing AI review and being able to produce a trusted binary are the same
    /// gate), the Linux review host has no Xcode/codesign and structurally cannot hold the Apple
    /// signing identity for this app -- see `macos/FocusLock/RELEASE.md`. This pinned key closes
    /// that gap with a second, independent signature that CAN live only on the review host, checked
    /// *in addition to* (not instead of) `pinnedUpdateTeamID` and the SHA-256 check below.
    ///
    /// **Empty by default, and `UpdateManager` refuses to install anything while it's empty** --
    /// same fail-closed stance as `pinnedUpdateTeamID`. Fill in your own host's public key here and
    /// rebuild before relying on auto-update.
    public static let pinnedReviewAttestationPublicKey = "VdwILWyejzNhnL+XSrhts5//Yae9qKJGhMHlNUHmKok="

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
