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
    /// Full path to the .app bundle, e.g. "/Applications/Accountable2You.app". Used both to
    /// apply the immutable flag and to relaunch it via `open`.
    public let bundlePath: String

    public init(displayName: String, executableName: String, bundlePath: String) {
        self.displayName = displayName
        self.executableName = executableName
        self.bundlePath = bundlePath
    }
}

/// The full state the daemon owns and persists to a root-owned file. The GUI app only ever
/// sees a copy of this via `getStatus`; it can never write it directly.
///
/// Blocking is unconditional and permanent: anything in `blockedApps`/`blockedDomains` is
/// enforced 24/7, with no timer or session to wait out. The only way off the list is removal,
/// which the daemon restricts to the Guardian admin account. `protectedApps` are the inverse:
/// kept alive and undeletable rather than blocked. `dnsEnforcementEnabled` mandates Cloudflare's
/// content-filtering DNS system-wide and blocks alternate resolvers, same asymmetry: anyone can
/// turn it on, only the Guardian can turn it off.
public struct FocusLockState: Codable, Sendable {
    public var blockedApps: [BlockedApp]
    public var blockedDomains: [String]
    public var protectedApps: [ProtectedApp]
    public var dnsEnforcementEnabled: Bool

    public init(
        blockedApps: [BlockedApp] = [],
        blockedDomains: [String] = [],
        protectedApps: [ProtectedApp] = [],
        dnsEnforcementEnabled: Bool = false
    ) {
        self.blockedApps = blockedApps
        self.blockedDomains = blockedDomains
        self.protectedApps = protectedApps
        self.dnsEnforcementEnabled = dnsEnforcementEnabled
    }

    // Custom decode so a state.json written before `dnsEnforcementEnabled` existed doesn't fail
    // to decode wholesale and get silently replaced with a blank state (losing every existing
    // blocked/protected entry) -- missing keys just default instead.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        blockedApps = try container.decodeIfPresent([BlockedApp].self, forKey: .blockedApps) ?? []
        blockedDomains = try container.decodeIfPresent([String].self, forKey: .blockedDomains) ?? []
        protectedApps = try container.decodeIfPresent([ProtectedApp].self, forKey: .protectedApps) ?? []
        dnsEnforcementEnabled = try container.decodeIfPresent(Bool.self, forKey: .dnsEnforcementEnabled) ?? false
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
