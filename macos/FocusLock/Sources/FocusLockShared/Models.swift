import Foundation

/// An app blocked by executable/process name (matched against running processes). Bundle
/// identifier is kept for display/lookup but isn't what enforcement matches on, since a daemon
/// scanning `proc_listpids` sees process names, not bundle IDs.
public struct BlockedApp: Codable, Hashable, Identifiable, Sendable {
    public var id: String { executableName }
    public let displayName: String
    public let executableName: String
    public let bundleIdentifier: String?

    public init(displayName: String, executableName: String, bundleIdentifier: String? = nil) {
        self.displayName = displayName
        self.executableName = executableName
        self.bundleIdentifier = bundleIdentifier
    }
}

/// An app that must stay running and installed, e.g. an accountability app whose reporting you
/// want to be unable to circumvent. Enforcement is the mirror of `BlockedApp`: instead of killing
/// it on sight, the daemon relaunches it if it's not running, and locks its bundle with the
/// filesystem-level `schg` (system-immutable) flag so it can't be deleted or moved -- a flag only
/// root can set or clear, so a Standard account can't touch it even with `sudo` (no admin
/// password to give sudo in the first place).
public struct ProtectedApp: Codable, Hashable, Identifiable, Sendable {
    public var id: String { executableName }
    public let displayName: String
    public let executableName: String
    /// Full path to the .app bundle, e.g. "/Applications/Safari.app". Used both to
    /// apply the immutable flag and to relaunch it via `open`.
    public let bundlePath: String

    public init(displayName: String, executableName: String, bundlePath: String) {
        self.displayName = displayName
        self.executableName = executableName
        self.bundlePath = bundlePath
    }
}

/// A dashboard-authored rule targeting THIS device (see `filter-server/lockprofile_service.py`'s
/// global rule library, `RULES_PATH` -- a rule now names its own `deviceIds`, filtered onto this
/// device server-side before it ever reaches here, same as always) -- "block every name in
/// `executableNames` unless every habit in `requiredHabitIds` is done today, and only during this
/// schedule window." Always windowed: the dashboard wizard always sets a schedule (see Android's
/// `HabitRuleManager`'s own "Phase 5" doc comment, which this mirrors), so there's no
/// non-windowed/grant-duration case to handle here at all -- unlike Android, which also has
/// locally-authored non-windowed rules with no Mac equivalent. `executableNames` can name more
/// than one app (the dashboard's targetApps, see api.ts's Rule doc comment) -- mirrors Android's
/// `HabitRule.targetPackages` supporting multiple targets per rule, just as a plain array instead
/// of a delimited string since this has no Room-style single-column constraint to work around.
/// A rule's `targetWebsites` (if any) has no Mac-side representation here -- website blocking is
/// enforced purely server-side via DNS (see `filter-server/dns_classify_mux.py`), not something
/// this daemon evaluates itself. `windowStartMinute`/`windowEndMinute` are minutes-since-midnight
/// (0...1439); `daysOfWeek` uses the dashboard's own JS `Date.getDay()` convention
/// (0=Sunday...6=Saturday), NOT `Calendar`'s. Never persisted -- `RuleBlockEnforcer` re-derives
/// the live block/unblock verdict fresh on every tick from this plus
/// `FocusLockState.globalHabitsCache`, the same way Android's `isTargetUnlocked` does for its own
/// synthetic dashboard rules.
public struct MacRule: Codable, Sendable {
    public let id: String
    public let executableNames: [String]
    public let requiredHabitIds: [String]
    public let windowStartMinute: Int
    public let windowEndMinute: Int
    public let daysOfWeek: Set<Int>

    public init(id: String, executableNames: [String], requiredHabitIds: [String], windowStartMinute: Int, windowEndMinute: Int, daysOfWeek: Set<Int>) {
        self.id = id
        self.executableNames = executableNames
        self.requiredHabitIds = requiredHabitIds
        self.windowStartMinute = windowStartMinute
        self.windowEndMinute = windowEndMinute
        self.daysOfWeek = daysOfWeek
    }
}

/// One entry from the global habit library shared across every device (see
/// `filter-server/lockprofile_service.py`'s `HABITS_PATH`) -- `doneToday` is computed
/// server-side from whichever device most recently reported this habit's completion (see
/// `GET /dashboard-api/habits`), so this Mac never needs to know who verified it or how.
public struct GlobalHabit: Codable, Sendable {
    public let id: String
    public let name: String
    public let doneToday: Bool

    public init(id: String, name: String, doneToday: Bool) {
        self.id = id
        self.name = name
        self.doneToday = doneToday
    }
}

/// Flattened, decode-only-relevant subset of what `GET /dashboard-api/devices/<id>/settings`
/// returns (see `filter-server/lockprofile_service.py`), cached on `FocusLockState` by
/// `DashboardConfigSync` (Phase 1 of extending `SERVER_DRIVEN_CONFIG_PLAN.md`-style dashboard
/// control to the Mac -- see that doc, originally written for the Android app). Deliberately flat
/// (not a re-declaration of the server's nested JSON shape) so this round-trips through
/// `FocusLockCodec`'s ordinary synthesized Codable when `state.json` is saved/loaded, the same as
/// every other field on `FocusLockState` -- the server's own nested/list-of-objects shape is
/// parsed and flattened once, in `DashboardConfigSync.fetch`'s own raw decode type, not here.
public struct DashboardDeviceSettingsCache: Codable, Sendable {
    public let platform: String?
    public let updatedAt: Double?
    /// From the server's `vpnFilter.enabled` -- see `DashboardConfigSync`'s doc comment for why
    /// this maps to `dnsEnforcementEnabled` specifically, not a DNS+proxy composite.
    public let contentFilterEnabled: Bool?
    /// Extracted `appId` values from the server's `blockedApps: [{appId, addedAt}]` -- executable
    /// names on this platform (see `BlockedApp`'s doc comment on `executableName`).
    public let blockedApps: [String]
    /// From the server's `protectedApps: [{displayName, executableName, bundlePath, addedAt}]`
    /// -- macos-only, no Android equivalent. Reuses `ProtectedApp` directly rather than another
    /// flattened representation since its shape already matches one-to-one.
    public let protectedApps: [ProtectedApp]
    /// macos-only fields below -- all nil ("no opinion yet") unless the server explicitly has a
    /// value (see `_default_device_settings`'s comment on why these default to `None`/null
    /// server-side rather than a concrete value).
    public let proxyFilterEnabled: Bool?
    public let proxyFilterForceViaFirewall: Bool?
    public let cloudFilterHost: String?
    public let cloudFilterEnabled: Bool?
    /// This device's own dashboard-authored rules -- see `MacRule`'s doc comment. Empty if none
    /// configured (not distinguished from "no opinion yet" the way the scalar fields above are,
    /// since an empty rule list is unambiguous: no rules means nothing is rule-blocked, which is
    /// exactly correct behavior, unlike an empty `blockedApps` colliding with "clear everything").
    public let rules: [MacRule]

    public init(
        platform: String?,
        updatedAt: Double?,
        contentFilterEnabled: Bool?,
        blockedApps: [String],
        protectedApps: [ProtectedApp],
        proxyFilterEnabled: Bool?,
        proxyFilterForceViaFirewall: Bool?,
        cloudFilterHost: String?,
        cloudFilterEnabled: Bool?,
        rules: [MacRule]
    ) {
        self.platform = platform
        self.updatedAt = updatedAt
        self.contentFilterEnabled = contentFilterEnabled
        self.blockedApps = blockedApps
        self.protectedApps = protectedApps
        self.proxyFilterEnabled = proxyFilterEnabled
        self.proxyFilterForceViaFirewall = proxyFilterForceViaFirewall
        self.cloudFilterHost = cloudFilterHost
        self.cloudFilterEnabled = cloudFilterEnabled
        self.rules = rules
    }
}

/// The full state the daemon owns and persists to a root-owned file. The GUI app only ever
/// sees a copy of this via `getStatus`; it can never write it directly.
///
/// Blocking is unconditional and permanent: anything in `blockedApps`/`blockedDomains` is
/// enforced 24/7, with no timer or session to wait out. The only way off the list is removal,
/// which the daemon restricts to the Guardian admin account. `protectedApps` are the inverse:
/// kept alive and undeletable rather than blocked. `dnsEnforcementEnabled` mandates content-
/// filtering DNS system-wide and blocks alternate resolvers, same asymmetry: anyone can turn it
/// on, only the Guardian can turn it off. `cloudFilterHost`/`cloudFilterEnabled` pick which
/// resolver is used while DNS enforcement is on (a configurable cloud filter as primary, falling
/// back to Cloudflare Family if disabled or unresolved -- see `DNSEnforcer`).
public struct FocusLockState: Codable, Sendable {
    public var blockedApps: [BlockedApp]
    public var blockedDomains: [String]
    public var protectedApps: [ProtectedApp]
    public var dnsEnforcementEnabled: Bool
    public var cloudFilterHost: String
    public var cloudFilterEnabled: Bool

    /// When on, the daemon points every network service's system HTTP/HTTPS proxy at the
    /// filter-server's mitmproxy (`proxyHost:proxyPort`) and re-asserts it every tick, so the Mac's
    /// browser traffic is content-filtered the same way the phone's is. Protection-increasing to
    /// turn on (immediate); turning off is passcode-gated (`disableProxyEnforcement`).
    /// Fail-open: `ProxyEnforcer` only sets the proxy when it's reachable AND the proxy password is
    /// provisioned -- otherwise it removes the proxy so browsing never breaks. Off by default.
    public var proxyEnforcementEnabled: Bool
    /// Additionally installs a pf rule blocking direct outbound :80/:443 to everything except the
    /// proxy, so even non-proxy-aware apps are forced through the filter (and the proxy can't be
    /// sidestepped by just turning the system proxy off). MUCH more aggressive -- it also blocks
    /// cert-pinned apps that can't be MITM'd -- so it's off by default and, critically, `PFBlocker`
    /// only applies it while the proxy is confirmed reachable this tick; if the proxy goes down the
    /// rule is lifted within one tick, so it can never take the machine offline.
    public var forceProxyViaFirewall: Bool
    public var proxyHost: String
    public var proxyPort: Int
    /// Live status, not persisted config: whether `LockProfileGuard` last saw the lock profile
    /// (see GUARDIAN_SETUP.md §5) installed. `XPCService.getStatus` overlays this from
    /// `LockProfileGuard.lastKnownState` on every reply rather than trusting whatever value was
    /// last written to state.json, since it can go stale the moment the profile is removed.
    public var lockProfileInstalled: Bool

    /// Live status, not persisted config: whether `VPNGuard` last saw a VPN carrying the machine's
    /// traffic (which bypasses the content filter entirely). Overlaid by `XPCService.getStatus` from
    /// `VPNGuard.lastKnownState` the same way `lockProfileInstalled` is, for the same reason.
    public var vpnActive: Bool

    /// Live status, not persisted config: whether `DNSEnforcer` last actually reached the cloud
    /// filter host, as opposed to falling back to Cloudflare Family. Overlaid by `XPCService.getStatus`
    /// from `DNSEnforcer.cloudFilterHostReachable` the same way `lockProfileInstalled` is. Defaults
    /// to `true` (both here and when missing from an old state.json) so a value that hasn't been
    /// overlaid yet never LOOKS like an outage that isn't real.
    public var cloudFilterHostReachable: Bool

    /// Live status, not persisted config: `FocusLockConstants.appVersionCode` as compiled into the
    /// DAEMON binary actually answering this `getStatus` call -- overlaid the same way
    /// `lockProfileInstalled` is. The GUI and daemon are separate processes/binaries that don't
    /// necessarily restart in lockstep (e.g. the daemon silently self-updates on the hourly
    /// automatic check while a still-open GUI window keeps running its own older binary until
    /// quit and reopened -- see RELEASE.md), so the GUI's own compiled-in `appVersionCode` is NOT
    /// reliably "the build actually running" and must never be used for the "Build N" label or an
    /// update-available comparison -- both should read this field instead. 0 means "not yet
    /// overlaid" (e.g. a state.json read before the first live `getStatus` reply), never a real
    /// build number.
    public var daemonVersionCode: Int

    /// The secret that gates every protection-reducing call once it's set. **Never leaves the
    /// daemon**: `XPCService.getStatus` nils this out and reports `passcodeConfigured` instead, so
    /// the digest isn't handed to callers who could grind it offline at their leisure.
    public var guardianPasscode: PasscodeRecord?

    /// Transport-only mirror of `guardianPasscode != nil`, overlaid by `getStatus` the same way
    /// `lockProfileInstalled` is. Not meaningful in the persisted file.
    public var passcodeConfigured: Bool

    /// Master switch for the ENTIRE app, not just content filtering -- `EnforcementLoop` skips
    /// everything (DNS, proxy, pf, blocked/protected apps, even lock-profile/VPN/integrity
    /// monitoring) while this is false. Only ever set false by `XPCService.killSwitch` and only
    /// ever set back to true by `XPCService.restoreFromKillSwitch`; nothing else touches it. Not
    /// exposed anywhere else on purpose -- this isn't a Guardian setting, it's the kill switch's
    /// own persisted record of "everything is supposed to be off", so a daemon that somehow gets
    /// re-bootstrapped without going through `restoreFromKillSwitch` doesn't silently resume.
    public var protectionEnabled: Bool

    /// Best-effort cache of the last successfully-fetched dashboard config for this device (see
    /// `DashboardConfigSync`), surfaced by `otterlingctl status`/the GUI so a guardian can
    /// confirm connectivity ("last synced: Xm ago"). Not sensitive, so unlike `guardianPasscode`
    /// it's left untouched by `redactedForStatus()`.
    public var dashboardConfigCache: DashboardDeviceSettingsCache?
    public var dashboardConfigLastFetchedAt: Date?

    /// Executable names `DashboardConfigSync.reconcile` itself has added to `blockedApps`/
    /// `protectedApps` -- NOT entries a guardian added locally via the GUI/CLI. `reconcile` only
    /// ever schedules REMOVAL for a name already in these sets; a local-only addition it doesn't
    /// recognize as its own is left alone even once it's absent from the dashboard's list.
    /// Without this, a guardian adding e.g. "Steam" to `blockedApps` via the local GUI (which
    /// never pushes to the server) would look identical, on the next sync, to "the dashboard no
    /// longer wants this blocked" -- and get silently scheduled for removal by a system the
    /// guardian never even opened. Cleared for a name the moment its removal is scheduled (not
    /// only once the removal matures), and populated the moment `reconcile` adds a name -- so
    /// this always reflects "did dashboard sync add the CURRENTLY-blocked/protected entry with
    /// this name," not a historical record.
    public var dashboardManagedBlockedApps: Set<String>
    public var dashboardManagedProtectedApps: Set<String>

    /// Best-effort cache of the global habit library + live completion state (see
    /// `GlobalHabit`), fetched separately from `dashboardConfigCache` (a different endpoint --
    /// `GET /dashboard-api/habits`, not per-device). `RuleBlockEnforcer` reads this alongside
    /// `dashboardConfigCache?.rules` to compute which apps are currently rule-blocked; never
    /// written anywhere else.
    public var globalHabitsCache: [GlobalHabit]

    /// Set by `UpdateCheckLoop` right before it restarts the daemon to finish installing an
    /// automatic background update -- persisted (unlike e.g. `lockProfileInstalled`'s "live
    /// status") specifically so it survives that restart, and so the GUI can still notice-and-
    /// notify even if it wasn't running at the moment the install actually happened. The GUI
    /// (`FocusLockViewModel.refreshOnce`) diffs `lastAutoUpdateVersion` against the last version
    /// it already showed a local notification for, so this isn't re-notified on every 1s poll.
    public var lastAutoUpdateVersion: String?
    public var lastAutoUpdateAt: Date?

    public init(
        blockedApps: [BlockedApp] = [],
        blockedDomains: [String] = [],
        protectedApps: [ProtectedApp] = [],
        // Only the true "no state.json exists yet" path uses this default (see StateStore) --
        // fresh installs ship with NSFW content filtering already on, matching the product's
        // primary job. An existing install upgrading from a build that predates this field always
        // decodes real JSON (even if this specific key is missing from it), which hits the
        // decoder below instead and correctly defaults to `false` there -- so an existing user's
        // "off" is never silently flipped on by this change.
        dnsEnforcementEnabled: Bool = true,
        cloudFilterHost: String = FocusLockConstants.defaultCloudFilterHost,
        cloudFilterEnabled: Bool = true,
        // Off by default even on a fresh install: routing the Mac through the MITM proxy requires
        // provisioning the proxy password + trusting the mitm CA first (Scripts/setup_mac_proxy),
        // so it can't be silently on out of the box.
        proxyEnforcementEnabled: Bool = false,
        forceProxyViaFirewall: Bool = false,
        proxyHost: String = FocusLockConstants.defaultCloudFilterHost,
        proxyPort: Int = FocusLockConstants.defaultProxyPort,
        lockProfileInstalled: Bool = false,
        vpnActive: Bool = false,
        cloudFilterHostReachable: Bool = true,
        daemonVersionCode: Int = 0,
        guardianPasscode: PasscodeRecord? = nil,
        passcodeConfigured: Bool = false,
        protectionEnabled: Bool = true,
        dashboardConfigCache: DashboardDeviceSettingsCache? = nil,
        dashboardConfigLastFetchedAt: Date? = nil,
        dashboardManagedBlockedApps: Set<String> = [],
        dashboardManagedProtectedApps: Set<String> = [],
        globalHabitsCache: [GlobalHabit] = [],
        lastAutoUpdateVersion: String? = nil,
        lastAutoUpdateAt: Date? = nil
    ) {
        self.blockedApps = blockedApps
        self.blockedDomains = blockedDomains
        self.protectedApps = protectedApps
        self.dnsEnforcementEnabled = dnsEnforcementEnabled
        self.cloudFilterHost = cloudFilterHost
        self.cloudFilterEnabled = cloudFilterEnabled
        self.proxyEnforcementEnabled = proxyEnforcementEnabled
        self.forceProxyViaFirewall = forceProxyViaFirewall
        self.proxyHost = proxyHost
        self.proxyPort = proxyPort
        self.lockProfileInstalled = lockProfileInstalled
        self.vpnActive = vpnActive
        self.cloudFilterHostReachable = cloudFilterHostReachable
        self.daemonVersionCode = daemonVersionCode
        self.guardianPasscode = guardianPasscode
        self.passcodeConfigured = passcodeConfigured
        self.protectionEnabled = protectionEnabled
        self.dashboardConfigCache = dashboardConfigCache
        self.dashboardConfigLastFetchedAt = dashboardConfigLastFetchedAt
        self.dashboardManagedBlockedApps = dashboardManagedBlockedApps
        self.dashboardManagedProtectedApps = dashboardManagedProtectedApps
        self.globalHabitsCache = globalHabitsCache
        self.lastAutoUpdateVersion = lastAutoUpdateVersion
        self.lastAutoUpdateAt = lastAutoUpdateAt
    }

    // Custom decode so a state.json written before `dnsEnforcementEnabled` (or these newer cloud
    // filter fields) existed doesn't fail to decode wholesale and get silently replaced with a
    // blank state (losing every existing blocked/protected entry) -- missing keys just default
    // instead. `dnsEnforcementEnabled` defaults to `false` here specifically (not the `init`
    // default above) so upgrading an existing install never turns content filtering on without
    // the Guardian's say-so; the cloud filter fields default on since they only pick which
    // resolver an *already-enabled* DNS enforcement uses.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        blockedApps = try container.decodeIfPresent([BlockedApp].self, forKey: .blockedApps) ?? []
        blockedDomains = try container.decodeIfPresent([String].self, forKey: .blockedDomains) ?? []
        protectedApps = try container.decodeIfPresent([ProtectedApp].self, forKey: .protectedApps) ?? []
        dnsEnforcementEnabled = try container.decodeIfPresent(Bool.self, forKey: .dnsEnforcementEnabled) ?? false
        cloudFilterHost = try container.decodeIfPresent(String.self, forKey: .cloudFilterHost) ?? FocusLockConstants.defaultCloudFilterHost
        cloudFilterEnabled = try container.decodeIfPresent(Bool.self, forKey: .cloudFilterEnabled) ?? true
        // Both proxy toggles default OFF for an existing install that predates them -- turning the
        // Mac's whole traffic through a MITM proxy is never something an upgrade should switch on
        // without the Guardian's explicit say-so (and CA/password provisioning).
        proxyEnforcementEnabled = try container.decodeIfPresent(Bool.self, forKey: .proxyEnforcementEnabled) ?? false
        forceProxyViaFirewall = try container.decodeIfPresent(Bool.self, forKey: .forceProxyViaFirewall) ?? false
        proxyHost = try container.decodeIfPresent(String.self, forKey: .proxyHost) ?? FocusLockConstants.defaultCloudFilterHost
        proxyPort = try container.decodeIfPresent(Int.self, forKey: .proxyPort) ?? FocusLockConstants.defaultProxyPort
        lockProfileInstalled = try container.decodeIfPresent(Bool.self, forKey: .lockProfileInstalled) ?? false
        vpnActive = try container.decodeIfPresent(Bool.self, forKey: .vpnActive) ?? false
        cloudFilterHostReachable = try container.decodeIfPresent(Bool.self, forKey: .cloudFilterHostReachable) ?? true
        daemonVersionCode = try container.decodeIfPresent(Int.self, forKey: .daemonVersionCode) ?? 0
        guardianPasscode = try container.decodeIfPresent(PasscodeRecord.self, forKey: .guardianPasscode)
        // Present in a `getStatus` payload (where `guardianPasscode` has deliberately been stripped,
        // so it can't be derived); absent from a state.json written before this field existed, where
        // deriving it is exactly right. `StateStore` re-derives it after loading from disk regardless,
        // so a hand-edited file can't make the daemon report a passcode it doesn't have.
        passcodeConfigured = try container.decodeIfPresent(Bool.self, forKey: .passcodeConfigured) ?? (guardianPasscode != nil)
        // Missing key defaults to true (protection ON) -- both for a state.json written before
        // this field existed (must not retroactively look kill-switched) and for the ordinary
        // case of a fresh install. The ONLY way this is ever actually false is `killSwitch`
        // explicitly persisting it, so a missing/absent value never means "off".
        protectionEnabled = try container.decodeIfPresent(Bool.self, forKey: .protectionEnabled) ?? true
        // Absent from a state.json written before this landed, and equally fine to default to
        // "never synced" on a fresh install -- there's no meaningful default to invent here, and
        // a missing/nil pair just means "confirm status not yet known" wherever it's displayed.
        dashboardConfigCache = try container.decodeIfPresent(DashboardDeviceSettingsCache.self, forKey: .dashboardConfigCache)
        dashboardConfigLastFetchedAt = try container.decodeIfPresent(Date.self, forKey: .dashboardConfigLastFetchedAt)
        // Missing key defaults to empty -- correct both for a pre-existing state.json (nothing
        // was ever dashboard-managed before this field existed) and a fresh install (nothing has
        // synced yet). An empty set here just means reconcile treats every existing blockedApps/
        // protectedApps entry as local-only until it re-adds them itself, which is the safe
        // direction (never remove something it doesn't recognize as its own).
        dashboardManagedBlockedApps = try container.decodeIfPresent(Set<String>.self, forKey: .dashboardManagedBlockedApps) ?? []
        dashboardManagedProtectedApps = try container.decodeIfPresent(Set<String>.self, forKey: .dashboardManagedProtectedApps) ?? []
        globalHabitsCache = try container.decodeIfPresent([GlobalHabit].self, forKey: .globalHabitsCache) ?? []
        lastAutoUpdateVersion = try container.decodeIfPresent(String.self, forKey: .lastAutoUpdateVersion)
        lastAutoUpdateAt = try container.decodeIfPresent(Date.self, forKey: .lastAutoUpdateAt)
    }

    private enum CodingKeys: String, CodingKey {
        case blockedApps, blockedDomains, protectedApps, dnsEnforcementEnabled
        case cloudFilterHost, cloudFilterEnabled, lockProfileInstalled, vpnActive, cloudFilterHostReachable, daemonVersionCode
        case proxyEnforcementEnabled, forceProxyViaFirewall, proxyHost, proxyPort
        case guardianPasscode, passcodeConfigured
        case protectionEnabled, dashboardConfigCache, dashboardConfigLastFetchedAt
        case dashboardManagedBlockedApps, dashboardManagedProtectedApps, globalHabitsCache
        case lastAutoUpdateVersion, lastAutoUpdateAt
    }

    /// The copy handed to callers of `getStatus`: same state minus the passcode digest, with
    /// `passcodeConfigured` carrying the only bit a caller legitimately needs (whether a passcode
    /// exists at all). Keeping this here rather than inline in `XPCService` means every future
    /// caller of the status path gets the redaction by construction instead of by remembering to.
    public func redactedForStatus() -> FocusLockState {
        var copy = self
        copy.passcodeConfigured = guardianPasscode != nil
        copy.guardianPasscode = nil
        return copy
    }
}

/// What `XPCService.killSwitch` snapshots right before it clears everything, so
/// `restoreFromKillSwitch` can put DNS/proxy back exactly as they were rather than guessing a
/// default (e.g. the proxy might have already been off before the kill switch, and restore
/// shouldn't turn it on just because it CAN). See `FocusLockConstants.killSwitchSnapshotPath`.
public struct KillSwitchSnapshot: Codable, Sendable {
    public let dnsEnforcementEnabled: Bool
    public let proxyEnforcementEnabled: Bool
    public let forceProxyViaFirewall: Bool

    public init(dnsEnforcementEnabled: Bool, proxyEnforcementEnabled: Bool, forceProxyViaFirewall: Bool) {
        self.dnsEnforcementEnabled = dnsEnforcementEnabled
        self.proxyEnforcementEnabled = proxyEnforcementEnabled
        self.forceProxyViaFirewall = forceProxyViaFirewall
    }
}

/// Result returned across the XPC boundary for mutating calls.
public struct FocusLockResult: Codable, Sendable {
    public let success: Bool
    public let message: String?

    public init(success: Bool, message: String? = nil) {
        self.success = success
        self.message = message
    }

    public static let ok = FocusLockResult(success: true)

    public static func denied(_ reason: String) -> FocusLockResult {
        FocusLockResult(success: false, message: reason)
    }
}
