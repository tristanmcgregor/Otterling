import Foundation

/// Decides what `SudoBroker` is allowed to run as root, and hands back a parsed argument vector
/// rather than a string.
///
/// WHY THIS IS ITS OWN TYPE IN FocusLockShared: this logic used to live inside the
/// `FocusLockHelperd` executable target, where a test target cannot import it -- so the one piece
/// of code standing between an arbitrary caller and a root shell had no tests at all. It is pure
/// (no I/O, no process launching), so it belongs here where `FocusLockSharedTests` can cover it.
/// `SudoBroker` keeps the execution half.
///
/// THE BUG THIS FIXES: the previous design matched an allowlist with `hasPrefix` and then ran the
/// result through `/bin/bash -l -c`. Those two facts combined meant a command beginning with an
/// allowlisted prefix could carry anything after a `;` straight into a root shell, and the
/// substring denylist could not stop it -- a denylisted word is trivially assembled at runtime by
/// a shell that is already executing. Two changes close it, either of which would be sufficient
/// and both of which are applied:
///
///   1. A command containing shell metacharacters is refused outright, before any tier runs.
///   2. Approved commands are executed as argv, never handed to a shell (see `SudoBroker.execute`),
///      so a metacharacter that somehow got through would be an inert literal argument.
///
/// DELIBERATE COST: a genuinely-needed pipeline or redirect is now un-runnable through this
/// broker. That is the intended trade and it matches the original design's own stated stance --
/// "a false-positive deny just means ask again with more specificity, or if it's truly needed,
/// that's a conversation with your accountability partner. A false-negative allow is not." A
/// shell pipeline running as root, approved by an AI reviewer talked into it by a determined
/// operator, is precisely the outcome this broker exists to prevent.
public enum CommandPolicy {
    /// What to do with a submitted command. `needsReview` carries the parsed argv so the caller
    /// never re-parses (and so the thing reviewed is the thing executed).
    public enum Decision: Equatable {
        case denied(source: String, reason: String)
        case allowed(source: String, argv: [String], reason: String)
        case needsReview(argv: [String])
    }

    /// Characters that give a shell its power. Rejected anywhere in the command. Glob characters
    /// (`*`, `?`, `[`) are deliberately NOT here: with argv execution there is no shell to expand
    /// them, so they reach the target program as literal text, which is harmless and occasionally
    /// what the caller actually meant.
    static let forbiddenCharacters: Set<Character> = [
        ";", "|", "&", "$", "`", "(", ")", "<", ">", "\n", "\r", "\"", "'", "\\", "\0",
    ]

    /// Unconditional denials, checked before the allowlist. Kept broad and overlapping on purpose.
    /// Now defence-in-depth rather than the primary gate: with metacharacters refused and argv
    /// execution, these patterns can no longer be evaded by runtime string assembly, because there
    /// is no shell left to assemble anything.
    static let denylistPatterns: [String] = [
        "otterling", "focuslock", "app\\.otterling",
        "csrutil", "spctl\\s+--master-disable", "spctl\\s+--disable",
        "nvram", "firmwarepasswd", "bless\\s",
        "sudoers", "visudo",
        "dscl\\s+.*\\s+(append|merge|create|delete).*\\bgroup\\b", "dseditgroup", "sysadminctl",
        "fdesetup", "diskutil\\s+.*apfs\\s+(delete|erase)", "diskutil\\s+eraseDisk",
        "pfctl", "launchctl", "tccutil",
        "kill(all)?\\s+.*(focuslock|helperd|watchdog)",
        "codesign\\s+--remove-signature",
        "profiles\\s+(remove|-R)",
        "\\brm\\b.*-[a-zA-Z]*r",
    ]

    /// Executables refused outright, matched on BASENAME so a path prefix cannot evade the check
    /// (`/usr/bin/env bash` has to be caught as surely as `bash`). Two groups, one reason:
    /// argv-only execution removes the *shell*, but it does not remove every way to get arbitrary
    /// code run as root. A shell or interpreter invoked as argv is a complete escape from this
    /// entire policy -- `python3 /tmp/attacker.py` needs no metacharacters at all, and the caller
    /// can write that file without any privilege. So can anything with a documented shell escape
    /// (`find -exec`, `awk 'BEGIN{system()}'`, a pager, an editor).
    ///
    /// This is tier 1 doing its job locally. Tier 2 (AI review) would very likely catch these too,
    /// but tier 1 must hold on its own -- it is the tier that still works when the network is cut,
    /// which is the first thing a determined operator would try.
    static let forbiddenExecutables: Set<String> = [
        // Shells.
        "sh", "bash", "zsh", "ksh", "csh", "tcsh", "dash", "fish", "env",
        // Interpreters.
        "python", "python2", "python3", "perl", "ruby", "php", "node", "osascript",
        "swift", "lua", "tclsh", "expect", "gdb", "lldb",
        // Utilities with a documented shell/command escape.
        "find", "xargs", "awk", "gawk", "nawk", "sed", "vi", "vim", "nvim", "emacs", "ed",
        "less", "more", "man", "nano", "pico", "tar", "zip", "rsync", "git",
        "screen", "tmux", "socat", "nc", "ncat", "netcat", "telnet", "ftp", "smbclient",
    ]

    /// Exact `(executable, permitted subcommands)` pairs. `nil` subcommands means "any arguments,
    /// as long as they cleared the metacharacter check". Matched on the executable's BASENAME
    /// against an exact string -- never a prefix of the whole command line, which is the specific
    /// mistake that made the previous version exploitable.
    static let allowlist: [(executable: String, subcommands: Set<String>?)] = [
        ("brew", ["install", "upgrade", "uninstall", "update", "reinstall"]),
        ("softwareupdate", nil),
    ]

    public static func evaluate(command: String) -> Decision {
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !trimmed.isEmpty else {
            return .denied(source: "policy", reason: "Empty command.")
        }

        if let offender = trimmed.first(where: { forbiddenCharacters.contains($0) }) {
            return .denied(
                source: "policy",
                reason: "Command contains the shell metacharacter '\(offender)'. This broker runs "
                    + "commands directly, not through a shell, so pipelines, redirects, "
                    + "substitutions and chained commands are never permitted. Submit a single "
                    + "command with plain arguments."
            )
        }

        let argv = trimmed.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        guard let executable = argv.first else {
            return .denied(source: "policy", reason: "Empty command.")
        }

        let basename = (executable as NSString).lastPathComponent.lowercased()
        if forbiddenExecutables.contains(basename) {
            return .denied(
                source: "denylist",
                reason: "'\(basename)' is a shell, interpreter, or a program with a known command "
                    + "escape, and is never permitted through this broker -- approving one would "
                    + "hand over arbitrary root execution regardless of the rest of this policy."
            )
        }

        let lowered = trimmed.lowercased()
        if let matched = denylistPatterns.first(where: { matches(pattern: $0, in: lowered) }) {
            return .denied(
                source: "denylist",
                reason: "Matches denylist pattern '\(matched)'. This command is never permitted through this broker."
            )
        }

        for entry in allowlist where entry.executable == basename {
            guard let permitted = entry.subcommands else {
                return .allowed(source: "allowlist", argv: argv, reason: "Matches an always-approved common admin task.")
            }
            let subcommand = argv.count > 1 ? argv[1].lowercased() : ""
            guard permitted.contains(subcommand) else {
                return .denied(
                    source: "allowlist",
                    reason: "'\(basename) \(subcommand)' is not an approved subcommand. Permitted: "
                        + permitted.sorted().joined(separator: ", ") + "."
                )
            }
            return .allowed(source: "allowlist", argv: argv, reason: "Matches an always-approved common admin task.")
        }

        return .needsReview(argv: argv)
    }

    /// A broken pattern must not silently stop denying -- treat it as a match so it fails toward
    /// the safe side. Unchanged from the original implementation.
    static func matches(pattern: String, in text: String) -> Bool {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return true
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.firstMatch(in: text, options: [], range: range) != nil
    }
}
