import Foundation

/// A request to run a single shell command with root privilege, made by the Guardian's own
/// (eventually Standard, no direct sudo) account -- see `SudoBroker.swift`'s doc comment for the
/// full decision pipeline this feeds into.
public struct ElevatedCommandRequest: Codable {
    public let command: String
    public let reason: String

    public init(command: String, reason: String) {
        self.command = command
        self.reason = reason
    }
}

/// `source` is one of "denylist" (hardcoded, always-deny match), "allowlist" (hardcoded,
/// always-allow match), "ai_review" (server round-trip decided it), or "error" (anything went
/// wrong with the review round-trip itself -- treated as a deny, never as an allow, since this
/// whole system's point is to fail closed when uncertain).
public struct ElevatedCommandResult: Codable {
    public let approved: Bool
    public let source: String
    public let explanation: String
    public let stdout: String?
    public let stderr: String?
    public let exitCode: Int32?

    public init(
        approved: Bool,
        source: String,
        explanation: String,
        stdout: String? = nil,
        stderr: String? = nil,
        exitCode: Int32? = nil
    ) {
        self.approved = approved
        self.source = source
        self.explanation = explanation
        self.stdout = stdout
        self.stderr = stderr
        self.exitCode = exitCode
    }
}
