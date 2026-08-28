import Foundation
import Security

/// Decides whether a process connecting to `FocusLockHelperd`'s Mach service is actually one of
/// this app's own signed binaries.
///
/// WHY THIS EXISTS: the helper's LaunchDaemon plist declares `MachServices`, which registers the
/// service in launchd's *global* bootstrap namespace -- reachable by any process at any uid, not
/// just the GUI running as the console user. `ListenerDelegate` used to `return true`
/// unconditionally, so every method on `FocusLockXPCProtocol` was callable by anything on the
/// machine. That is not a subtle exposure: `requestElevatedCommand` runs commands as root, and
/// `killSwitch` disables the whole product. Both are deliberately un-passcoded, on the stated
/// assumption that only the Guardian's own signed GUI/`otterlingctl` can reach them. This type is
/// what makes that assumption true instead of aspirational.
///
/// HOW: the peer's pid is resolved to a `SecCode` and checked against a code requirement pinning
/// (a) an Apple-issued signing chain, (b) this build's Team ID, and (c) one of our own signing
/// identifiers. Verified against the real installed binaries -- `app.otterling` for the GUI app
/// bundle and `otterlingctl` for the CLI, both Team `FocusLockConstants.pinnedUpdateTeamID`, with `certificate
/// leaf[subject.OU]` carrying the Team ID (NOT the parenthesized suffix in the certificate's
/// common name -- see `FocusLockConstants.pinnedUpdateTeamID`'s doc comment for the two differing
/// on this project's own certificate).
///
/// KNOWN LIMITATION -- pid reuse: resolving a peer by pid is inherently racy, since a pid can in
/// principle be recycled between the kernel accepting the connection and this check running. The
/// airtight version keys off the connection's own `auditToken`, which is not exposed to Swift
/// without redeclaring private API. The race requires winning a very narrow window against a
/// freshly-exited signed Otterling process, and this check is a categorical improvement over the
/// unconditional `return true` it replaces; noted here so the ceiling is honest rather than
/// assumed. Every protection-*reducing* call is independently gated by admin group + passcode
/// (`XPCService.authorize`), so this is the outer of two doors, not the only one.
public enum XPCPeerValidator {
    /// Signing identifiers permitted to talk to the daemon. `app.otterling` is the GUI app bundle
    /// (the bundle-level `codesign` pass re-signs `Contents/MacOS/FocusLock` under the bundle
    /// identifier, so the running GUI presents this, not `FocusLock`). `otterlingctl` is the CLI,
    /// signed as a bare binary so its identifier is its filename. `FocusLockHelperd` and
    /// `FocusLockWatchdog` are deliberately absent -- neither is an XPC *client*.
    public static let allowedIdentifiers = ["app.otterling", "otterlingctl"]

    /// Describes a connecting process, for logging a rejection in a form that can actually be
    /// acted on ("which identifier / which team was refused") rather than a bare denial.
    public struct PeerIdentity {
        public let pid: pid_t
        public let identifier: String?
        public let teamID: String?

        public var description: String {
            "pid=\(pid) identifier=\(identifier ?? "<none>") team=\(teamID ?? "<none>")"
        }
    }

    /// The requirement every client must satisfy. Built from `teamID` rather than hardcoded so a
    /// rebuild under a different Apple account stays self-consistent.
    public static func requirementString(teamID: String) -> String {
        let identifierClause = allowedIdentifiers
            .map { "identifier \"\($0)\"" }
            .joined(separator: " or ")
        return "anchor apple generic and certificate leaf[subject.OU] = \"\(teamID)\" and (\(identifierClause))"
    }

    /// True when this process is itself a properly-signed, pinned-team binary -- i.e. we are a
    /// real installed build with a signing boundary to enforce, rather than an unsigned
    /// `swift build` product on a developer's machine.
    ///
    /// Gating enforcement on the DAEMON's own signature (not on a build flag) is what keeps this
    /// from being a fail-open switch an attacker can flip: replacing the daemon binary with an
    /// unsigned one already requires root, and root is past every boundary this app has. Meanwhile
    /// a dev build stays usable instead of the daemon refusing its own freshly-built CLI.
    private static func enforcementIsMeaningful(teamID: String) -> Bool {
        guard !teamID.isEmpty else { return false }
        // The daemon signs under `FocusLockHelperd`, which is deliberately not in
        // `allowedIdentifiers`, so self-check uses a team-only requirement.
        let selfRequirement = "anchor apple generic and certificate leaf[subject.OU] = \"\(teamID)\""
        return pid(getpid(), satisfies: selfRequirement)
    }

    /// The entry point `ListenerDelegate` calls. Returns nil to accept, or a human-readable reason
    /// to refuse.
    public static func rejectionReason(
        forPID peerPID: pid_t,
        teamID: String = FocusLockConstants.pinnedUpdateTeamID
    ) -> String? {
        guard enforcementIsMeaningful(teamID: teamID) else {
            // Loud, every time, and never silent: an operator reading the daemon log must be able
            // to tell "the door is open" from "the door is shut".
            log("[xpc-auth] this daemon is not signed by the pinned team (\(teamID.isEmpty ? "no team pinned" : teamID)) -- peer validation DISABLED for this build")
            return nil
        }

        guard pid(peerPID, satisfies: requirementString(teamID: teamID)) else {
            let peer = identity(ofPID: peerPID)
            return "peer failed code-signature validation (\(peer?.description ?? "pid=\(peerPID) <unreadable>"))"
        }
        return nil
    }

    // MARK: - Security framework plumbing

    /// Resolves `pid` to its running code object and checks it against `requirement`. Any failure
    /// to resolve or verify is a false (refuse), never a true -- an unverifiable peer is exactly
    /// the case this exists to stop.
    private static func pid(_ pid: pid_t, satisfies requirement: String) -> Bool {
        guard let code = code(forPID: pid) else { return false }
        var parsed: SecRequirement?
        guard SecRequirementCreateWithString(requirement as CFString, [], &parsed) == errSecSuccess,
              let parsed else {
            log("[xpc-auth] could not parse code requirement -- refusing: \(requirement)")
            return false
        }
        return SecCodeCheckValidityWithErrors(code, [], parsed, nil) == errSecSuccess
    }

    private static func code(forPID pid: pid_t) -> SecCode? {
        let attributes = [kSecGuestAttributePid: NSNumber(value: pid)] as CFDictionary
        var code: SecCode?
        guard SecCodeCopyGuestWithAttributes(nil, attributes, [], &code) == errSecSuccess else {
            return nil
        }
        return code
    }

    /// Best-effort identifier/team lookup, for the rejection message only. Never used to make the
    /// accept/refuse decision -- that goes through the requirement check above, which is the part
    /// that cannot be spoofed by a process choosing its own name.
    private static func identity(ofPID pid: pid_t) -> PeerIdentity? {
        guard let code = code(forPID: pid) else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else {
            return PeerIdentity(pid: pid, identifier: nil, teamID: nil)
        }
        var information: CFDictionary?
        guard SecCodeCopySigningInformation(staticCode, [], &information) == errSecSuccess,
              let dictionary = information as? [String: Any] else {
            return PeerIdentity(pid: pid, identifier: nil, teamID: nil)
        }
        return PeerIdentity(
            pid: pid,
            identifier: dictionary[kSecCodeInfoIdentifier as String] as? String,
            teamID: dictionary[kSecCodeInfoTeamIdentifier as String] as? String
        )
    }

    private static func log(_ message: String) {
        FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    }
}
