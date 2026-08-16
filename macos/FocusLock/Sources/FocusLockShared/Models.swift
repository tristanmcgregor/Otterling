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

/// The protection-reducing operations that can't be applied immediately. Everything here either
/// takes a block off the list or weakens the filter; the protection-*increasing* mirror of each
/// (adding a block, enabling DNS enforcement, raising the cooldown) stays immediate and ungated,
/// which is the same asymmetry the admin-group model had -- only the thing being asked for has
/// changed from "who are you" to "what can you produce, and can you still want this in N hours".
public enum PendingActionKind: String, Codable, Sendable {
    case removeBlockedApp
    case removeBlockedDomain
    case removeProtectedApp
    case disableDNSEnforcement
    case setCloudFilterHost
    case disableCloudFilter
    case lowerCooldownHours
    case clearPasscode

    /// Human-readable for the GUI/CLI pending list. `target` supplies the specifics.
    public var describedAction: String {
        switch self {
        case .removeBlockedApp: return "Unblock app"
        case .removeBlockedDomain: return "Unblock site"
        case .removeProtectedApp: return "Stop protecting app"
        case .disableDNSEnforcement: return "Turn off DNS enforcement"
        case .setCloudFilterHost: return "Repoint cloud filter to"
        case .disableCloudFilter: return "Turn off the cloud filter"
        case .lowerCooldownHours: return "Lower the cooldown to (hours)"
        case .clearPasscode: return "Remove the Guardian passcode"
        }
    }
}

/// A protection-reducing action that has been authorized with the passcode but hasn't matured yet.
/// `EnforcementLoop` applies it once `effectiveAt` passes; until then it sits in state where the
/// GUI/CLI can show it, and anyone -- no passcode needed -- can cancel it.
///
/// The cooldown is the half of this design that survives the user being their own admin: a local
/// admin can always `launchctl bootout` the daemon, but they can't do that *quietly* (the watchdog
/// re-bootstraps it and `TamperReporter` files the event), and they can't do it *impulsively* --
/// which is the failure mode that actually matters for self-imposed accountability software.
public struct PendingAction: Codable, Hashable, Identifiable, Sendable {
    public let id: String
    public let kind: PendingActionKind
    /// Executable name, domain, host, or numeric literal depending on `kind`; empty where the
    /// action needs no argument (e.g. `disableDNSEnforcement`).
    public let target: String
    public let requestedAt: Date
    public let effectiveAt: Date

    public init(
        id: String = UUID().uuidString,
        kind: PendingActionKind,
        target: String,
        requestedAt: Date,
        effectiveAt: Date
    ) {
        self.id = id
        self.kind = kind
        self.target = target
        self.requestedAt = requestedAt
        self.effectiveAt = effectiveAt
    }

    public func isMature(asOf now: Date = Date()) -> Bool {
        now >= effectiveAt
    }

    public var describedFully: String {
        target.isEmpty ? kind.describedAction : "\(kind.describedAction) \(target)"
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
    /// Live status, not persisted config: whether `LockProfileGuard` last saw the lock profile
    /// (see GUARDIAN_SETUP.md §5) installed. `XPCService.getStatus` overlays this from
    /// `LockProfileGuard.lastKnownState` on every reply rather than trusting whatever value was
    /// last written to state.json, since it can go stale the moment the profile is removed.
    public var lockProfileInstalled: Bool

    /// Live status, not persisted config: whether `VPNGuard` last saw a VPN carrying the machine's
    /// traffic (which bypasses the content filter entirely). Overlaid by `XPCService.getStatus` from
    /// `VPNGuard.lastKnownState` the same way `lockProfileInstalled` is, for the same reason.
    public var vpnActive: Bool

    /// The secret that gates every protection-reducing call once it's set. **Never leaves the
    /// daemon**: `XPCService.getStatus` nils this out and reports `passcodeConfigured` instead, so
    /// the digest isn't handed to callers who could grind it offline at their leisure.
    public var guardianPasscode: PasscodeRecord?

    /// Transport-only mirror of `guardianPasscode != nil`, overlaid by `getStatus` the same way
    /// `lockProfileInstalled` is. Not meaningful in the persisted file.
    public var passcodeConfigured: Bool

    /// How long an authorized protection-reducing action waits before it actually applies. Raising
    /// it is immediate and ungated; lowering it is itself a pending action, or the cooldown would
    /// be trivially self-defeating (set it to zero, then remove everything instantly).
    public var cooldownHours: Double

    /// Authorized-but-not-yet-matured actions, applied by `EnforcementLoop` once due.
    public var pendingActions: [PendingAction]

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
        lockProfileInstalled: Bool = false,
        vpnActive: Bool = false,
        guardianPasscode: PasscodeRecord? = nil,
        passcodeConfigured: Bool = false,
        cooldownHours: Double = FocusLockConstants.defaultCooldownHours,
        pendingActions: [PendingAction] = []
    ) {
        self.blockedApps = blockedApps
        self.blockedDomains = blockedDomains
        self.protectedApps = protectedApps
        self.dnsEnforcementEnabled = dnsEnforcementEnabled
        self.cloudFilterHost = cloudFilterHost
        self.cloudFilterEnabled = cloudFilterEnabled
        self.lockProfileInstalled = lockProfileInstalled
        self.vpnActive = vpnActive
        self.guardianPasscode = guardianPasscode
        self.passcodeConfigured = passcodeConfigured
        self.cooldownHours = cooldownHours
        self.pendingActions = pendingActions
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
        lockProfileInstalled = try container.decodeIfPresent(Bool.self, forKey: .lockProfileInstalled) ?? false
        vpnActive = try container.decodeIfPresent(Bool.self, forKey: .vpnActive) ?? false
        guardianPasscode = try container.decodeIfPresent(PasscodeRecord.self, forKey: .guardianPasscode)
        // Present in a `getStatus` payload (where `guardianPasscode` has deliberately been stripped,
        // so it can't be derived); absent from a state.json written before this field existed, where
        // deriving it is exactly right. `StateStore` re-derives it after loading from disk regardless,
        // so a hand-edited file can't make the daemon report a passcode it doesn't have.
        passcodeConfigured = try container.decodeIfPresent(Bool.self, forKey: .passcodeConfigured) ?? (guardianPasscode != nil)
        // An install that predates the cooldown gets the default rather than 0 -- decoding a
        // missing key as "no cooldown" would silently hand every existing user instant removals,
        // which is the exact behaviour this field exists to prevent.
        cooldownHours = try container.decodeIfPresent(Double.self, forKey: .cooldownHours) ?? FocusLockConstants.defaultCooldownHours
        pendingActions = try container.decodeIfPresent([PendingAction].self, forKey: .pendingActions) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case blockedApps, blockedDomains, protectedApps, dnsEnforcementEnabled
        case cloudFilterHost, cloudFilterEnabled, lockProfileInstalled, vpnActive
        case guardianPasscode, passcodeConfigured, cooldownHours, pendingActions
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
