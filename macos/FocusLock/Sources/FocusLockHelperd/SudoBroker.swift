import Foundation
import FocusLockShared

/// Privilege-elevation broker for the point at which the Guardian's own macOS account becomes
/// Standard (no direct sudo) -- see project notes on why: a passcode gate on an admin
/// account is a same-privilege-level watcher the account can always outrank, since it holds root
/// either way. Actually changing the account's privilege level creates a real boundary instead --
/// the Guardian's shell genuinely cannot run `sudo` anymore, and every privileged command instead
/// goes through this daemon (already root, already the trust boundary for everything else in this
/// app) via `requestElevatedCommand`.
///
/// NOT ACTIVE until the account is actually converted to Standard -- until then this exists
/// alongside normal sudo access, buildable and testable without depending on the conversion itself.
///
/// The decision half lives in `FocusLockShared.CommandPolicy` (pure, and therefore tested -- see
/// that type's doc comment for the shell-injection bug this split was made to fix). This file is
/// the execution half. Decision pipeline, first match wins:
///   1. `CommandPolicy` -- metacharacter refusal, then the local denylist, then the local
///      allowlist. No network dependency, so a Standard account cutting network access first
///      cannot defeat the gate that way.
///   2. `ai_review` -- a round-trip to the filter-server for anything tier 1 didn't resolve. ANY
///      failure of that round-trip is a deny. This tier is the one place in the whole app that is
///      deliberately fail-CLOSED rather than fail-open: denying an admin command is always safe
///      and recoverable, unlike denying DNS/proxy which risks bricking the machine.
///
/// Every decision -- approved or denied, from any tier -- is reported via `TamperReporter` as a
/// `sudo_request_*` event, specifically so a *successful* social-engineering pass through tier 2
/// still reaches the accountability partner rather than disappearing silently the moment it's
/// approved.
enum SudoBroker {
    static func handle(command: String, reason: String) -> ElevatedCommandResult {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)

        let decision: ElevatedCommandResult
        let argv: [String]

        switch CommandPolicy.evaluate(command: trimmed) {
        case .denied(let source, let why):
            decision = ElevatedCommandResult(approved: false, source: source, explanation: why)
            argv = []
        case .allowed(let source, let parsed, let why):
            decision = ElevatedCommandResult(approved: true, source: source, explanation: why)
            argv = parsed
        case .needsReview(let parsed):
            decision = AIReviewClient.review(command: trimmed, reason: reason)
            argv = parsed
        }

        report(command: trimmed, reason: reason, decision: decision)

        guard decision.approved, !argv.isEmpty else { return decision }
        return execute(argv: argv, decision: decision)
    }

    /// Homebrew refuses outright to run as root ("Running Homebrew as root is extremely
    /// dangerous") -- no environment variable overrides that, by design on their end. Confirmed
    /// live 2026-08-18: an allowlisted `brew install wget` failed with "Error: $HOME must be set
    /// to run brew" because a LaunchDaemon's bare environment has no HOME at all, and even fixing
    /// that alone wouldn't be enough -- brew would then hit its root check next. Since `brew` is
    /// one of the allowlist's own explicit entries (not an edge case), this has to actually work,
    /// not just fail more descriptively.
    private static func isBrew(_ argv: [String]) -> Bool {
        guard let first = argv.first else { return false }
        return (first as NSString).lastPathComponent.lowercased() == "brew"
    }

    /// Root-safe console-user lookup, same `stat -f %u /dev/console` approach used elsewhere in
    /// this daemon (e.g. `XPCService.consoleUserUID`) -- duplicated locally rather than shared
    /// since it's a two-line call, not worth a new shared type for.
    private static func consoleUsername() -> String? {
        let output = ProcessRunner.runCapturingStdout("/usr/bin/stat", ["-f", "%Su", "/dev/console"])
        let user = output.trimmingCharacters(in: .whitespacesAndNewlines)
        return (user.isEmpty || user == "root") ? nil : user
    }

    /// Runs `argv` directly -- NEVER through a shell. `/usr/bin/env` is the launcher purely to get
    /// PATH resolution for a bare executable name (there is no shell to do it), and env does not
    /// interpret its arguments, so nothing in argv can become a new command. This is the structural
    /// half of the fix described in `CommandPolicy`: even if a metacharacter reached this point, it
    /// would arrive at the target program as a literal argument instead of a shell operator.
    private static func execute(argv: [String], decision: ElevatedCommandResult) -> ElevatedCommandResult {
        let process = Process()
        var environment = ProcessInfo.processInfo.environment
        environment.removeValue(forKey: "BASH_ENV")
        environment.removeValue(forKey: "ENV")

        if isBrew(argv), let username = consoleUsername() {
            // Runs as the logged-in console user instead of root -- `sudo -u <user> -i` gives a
            // real login shell environment with THEIR actual HOME/PATH/brew ownership, exactly how
            // brew expects to be invoked. Root can `sudo -u` to any user with no password prompt,
            // so this still requires no interaction. The command is passed as argv after `--`, not
            // as a `-c` string, so this is not a shell-injection path. Only brew gets this
            // treatment -- everything else is a genuine root-elevation request, which is the whole
            // point of this broker.
            process.executableURL = URL(fileURLWithPath: "/usr/bin/sudo")
            process.arguments = ["-u", username, "-i", "--"] + argv
        } else {
            process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
            environment["HOME"] = "/var/root"
            environment["USER"] = "root"
            environment["LOGNAME"] = "root"
            environment["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin"
            process.arguments = argv
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
        // Drained before waiting: a command producing more output than the pipe buffer holds would
        // otherwise block forever writing into a pipe nobody is reading, deadlocking this thread
        // against waitUntilExit().
        let stdout = String(data: stdoutPipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)
        let stderr = String(data: stderrPipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)
        process.waitUntilExit()

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
