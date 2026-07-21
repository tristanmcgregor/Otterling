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

/// The full state the daemon owns and persists to a root-owned file. The GUI app only ever
/// sees a copy of this via `getStatus`; it can never write it directly.
public struct FocusLockState: Codable, Sendable {
    public var blockedApps: [BlockedApp]
    public var blockedDomains: [String]
    public var sessionExpiresAt: Date?

    public init(blockedApps: [BlockedApp] = [], blockedDomains: [String] = [], sessionExpiresAt: Date? = nil) {
        self.blockedApps = blockedApps
        self.blockedDomains = blockedDomains
        self.sessionExpiresAt = sessionExpiresAt
    }

    public var isSessionActive: Bool {
        guard let expiresAt = sessionExpiresAt else { return false }
        return Date() < expiresAt
    }

    public var remainingSeconds: TimeInterval {
        guard let expiresAt = sessionExpiresAt else { return 0 }
        return max(0, expiresAt.timeIntervalSinceNow)
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
