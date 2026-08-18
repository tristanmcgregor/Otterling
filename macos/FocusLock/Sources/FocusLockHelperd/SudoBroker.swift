import Foundation
import FocusLockShared

/// Privilege-elevation broker for the point at which the Guardian's own macOS account becomes
/// Standard (no direct sudo) -- see project notes on why: a passcode/cooldown gate on an admin
/// account is a same-privilege-level watcher the account can always outrank, since it holds root
/// either way. Actually changing the account's privilege level creates a real boundary instead --
/// the Guardian's shell genuinely cannot run `sudo` anymore, and every privileged command instead
/// goes through this daemon (already root, already the trust boundary for everything else in this
/// app) via `requestElevatedCommand`.
///
/// NOT ACTIVE until the account is actually converted to Standard -- until then this exists
/// alongside normal sudo access, buildable and testable without depending on the conversion itself.
///
/// Decision pipeline, in order, first match wins:
///   1. `denylist` -- hardcoded, local, no network dependency. Anything touching this app's own
///      daemons/files/profiles/pf rules, or macOS's own security primitives (SIP, sudoers, firmware,
///      Recovery, admin group membership, FileVault), is an unconditional deny. This must never be
///      network-dependent: a Standard account cutting network access first must not be able to
///      defeat the gate that way, so this tier alone has to hold on its own.
///   2. `allowlist` -- hardcoded, local, small and conservative. Common, clearly-safe daily admin
///      tasks (package manager operations, OS software update) that would otherwise add friction to
///      every mundane task and train the Guardian to route around the broker out of frustration.
///   3. `ai_review` -- a round-trip to the filter-server, which asks an AI reviewer (the same
///      Claude-Code-CLI-based mechanism the release pipeline already uses, see SELF_LOCKOUT.md) with
///      the full command, the Guardian's stated reason, and this broker's own denylist/allowlist as
///      context, so it can recognize a command *engineered* to look benign while actually reaching
///      the same denylisted outcome (e.g. a roundabout multi-step path to the same file deletion).
///      ANY failure of this round-trip -- unreachable server, malformed response, ambiguous verdict
///      -- is a deny. This tier is the one place in the whole app that's deliberately fail-CLOSED
///      rather than fail-open: denying an admin command is always safe and recoverable (ask again,
///      fix connectivity, try a narrower command), unlike denying DNS/proxy which risks bricking
///      the machine. Those are different problems with different correct failure directions.
///
/// Every decision -- approved or denied, from any tier -- is reported via `TamperReporter` as a
/// `sudo_request_*` event, specifically so a *successful* social-engineering pass through tier 3
/// still reaches the accountability partner rather than disappearing silently the moment it's
/// approved.
enum SudoBroker {
    /// Substring/regex fragments matched case-insensitively against the full command string.
    /// Deliberately broad and overlapping rather than precise -- a false-positive deny just means
    /// "ask again with more specificity, or if it's truly needed, that's a conversation with your
    /// accountability partner," which is a fine failure mode. A false-negative allow is not.
    private static let denylistPatterns: [String] = [
        // This app's own components -- the entire reason this broker exists.
        "otterling", "focuslock", "app\\.otterling",
        // macOS security primitives that would either disable this app's protections indirectly
        // or reopen the exact Recovery Mode / re-promotion escape hatches this design relies on
        // being closed.
        "csrutil", "spctl\\s+--master-disable", "spctl\\s+--disable",
        "nvram", "firmwarepasswd", "bless\\s",
        "sudoers", "visudo",
        "dscl\\s+.*\\s+(append|merge|create|delete).*\\bgroup\\b", "dseditgroup", "sysadminctl",
        "fdesetup", "diskutil\\s+.*apfs\\s+(delete|erase)", "diskutil\\s+eraseDisk",
        "pfctl\\s+-d", "pfctl\\s+-F\\s+all",
        "launchctl\\s+(bootout|unload|remove|disable)",
        "kill(all)?\\s+.*(focuslock|helperd|watchdog)",
        "codesign\\s+--remove-signature",
        "profiles\\s+(remove|-R)",
        "tccutil",
        "rm\\s+.*-r.*f", // broad on purpose: catches `rm -rf`, `rm -fr`, `rm -Rf`, etc.
    ]

    /// Small and conservative on purpose -- see the type doc comment. Grows only with real,
    /// repeated friction, never speculatively.
    private static let allowlistPrefixes: [String] = [
        "brew install ", "brew upgrade ", "brew uninstall ", "brew update",
        "softwareupdate ",
    ]

    static func handle(command: String, reason: String) -> ElevatedCommandResult {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return ElevatedCommandResult(approved: false, source: "denylist", explanation: "Empty command.")
        }

        let decision = decide(command: trimmed, reason: reason)
        report(command: trimmed, reason: reason, decision: decision)

        guard decision.approved else { return decision }
        return execute(command: trimmed, decision: decision)
    }

    private static func decide(command: String, reason: String) -> ElevatedCommandResult {
        let lowered = command.lowercased()

        if let matched = denylistPatterns.first(where: { matches(pattern: $0, in: lowered) }) {
            return ElevatedCommandResult(
                approved: false, source: "denylist",
                explanation: "Matches denylist pattern '\(matched)'. This command is never permitted through this broker."
            )
        }

        if allowlistPrefixes.contains(where: { lowered.hasPrefix($0) }) {
            return ElevatedCommandResult(
                approved: true, source: "allowlist",
                explanation: "Matches an always-approved common admin task."
            )
        }

        return AIReviewClient.review(command: command, reason: reason)
    }

    private static func matches(pattern: String, in text: String) -> Bool {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            // A broken pattern must not silently stop denying -- treat as a match so it fails
            // toward the safe (deny) side rather than the unsafe (allow) side.
            return true
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.firstMatch(in: text, options: [], range: range) != nil
    }

    /// Homebrew refuses outright to run as root ("Running Homebrew as root is extremely
    /// dangerous") -- no environment variable overrides that, by design on their end. Confirmed
    /// live 2026-08-18: an allowlisted `brew install wget` failed with "Error: $HOME must be set
    /// to run brew" because a LaunchDaemon's bare environment has no HOME at all, and even fixing
    /// that alone wouldn't be enough -- brew would then hit its root check next. Since `brew` is
    /// one of this broker's own explicit allowlist entries (not an edge case), this has to
    /// actually work, not just fail more descriptively.
    private static func isBrewCommand(_ command: String) -> Bool {
        command.hasPrefix("brew ") || command == "brew"
    }

    /// Root-safe console-user lookup, same `stat -f %u /dev/console` approach used elsewhere in
    /// this daemon (e.g. `XPCService.consoleUserUID`) -- duplicated locally rather than shared
    /// since it's a two-line call, not worth a new shared type for.
    private static func consoleUsername() -> String? {
        let output = ProcessRunner.runCapturingStdout("/usr/bin/stat", ["-f", "%Su", "/dev/console"])
        let user = output.trimmingCharacters(in: .whitespacesAndNewlines)
        return (user.isEmpty || user == "root") ? nil : user
    }

    private static func execute(command: String, decision: ElevatedCommandResult) -> ElevatedCommandResult {
        let process = Process()
        var environment = ProcessInfo.processInfo.environment

        if isBrewCommand(command), let username = consoleUsername() {
            // Runs as the logged-in console user instead of root -- `sudo -u <user> -i` gives a
            // real login shell with THEIR actual HOME/PATH/brew ownership, exactly how brew
            // expects to be invoked normally (nobody types `sudo brew install` even with real
            // sudo access). Root can `sudo -u` to any user with no password prompt, so this still
            // requires no interaction. Only brew gets this treatment -- everything else in the
            // allowlist/AI-review tiers is a genuine root-elevation request, which is the whole
            // point of this broker, and shouldn't be silently downgraded to the user's own
            // (post-Standard-conversion, non-admin) privilege level.
            process.executableURL = URL(fileURLWithPath: "/usr/bin/sudo")
            process.arguments = ["-u", username, "-i", "--", "/bin/bash", "-l", "-c", command]
        } else {
            process.executableURL = URL(fileURLWithPath: "/bin/bash")
            // `-l` (login shell) so approved commands see a normal PATH -- a LaunchDaemon's
            // default environment is minimal. HOME/USER/LOGNAME aren't set by `-l` alone (those
            // come from the environment a real login session populates, which this process never
            // had), so they're set explicitly here for tools (like brew, before the check above
            // routes it elsewhere) that read them directly rather than deriving from getpwuid.
            environment["HOME"] = "/var/root"
            environment["USER"] = "root"
            environment["LOGNAME"] = "root"
            process.arguments = ["-l", "-c", command]
        }
        process.environment = environment

        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()
        process.standardOutput = stdoutPipe
        process.standardError = stderrPipe

        guard (try? process.run()) != nil else {
            return ElevatedCommandResult(
                approved: true, source: decision.source,
                explanation: decision.explanation + " (approved, but failed to launch)"
            )
        }
        process.waitUntilExit()

        let stdout = String(data: stdoutPipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)
        let stderr = String(data: stderrPipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)

        return ElevatedCommandResult(
            approved: true, source: decision.source, explanation: decision.explanation,
            stdout: stdout, stderr: stderr, exitCode: process.terminationStatus
        )
    }

    /// Fired for every decision regardless of outcome -- see the type doc comment for why approvals
    /// are reported too, not just denials.
    private static func report(command: String, reason: String, decision: ElevatedCommandResult) {
        let verdictWord = decision.approved ? "APPROVED" : "DENIED"
        let details = "[\(decision.source)] \(verdictWord): \"\(command)\" (reason given: \"\(reason)\") -- \(decision.explanation)"
        TamperReporter.report(
            type: decision.approved ? "sudo_request_approved" : "sudo_request_denied",
            details: details
        )
    }
}
