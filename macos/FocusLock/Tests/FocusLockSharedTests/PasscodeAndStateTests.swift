import XCTest
@testable import FocusLockShared

/// These cover the failure modes that wouldn't show up as a crash or a visibly broken app: a
/// passcode digest leaking to unprivileged callers, or a passcode silently vanishing across a
/// save.
final class PasscodeAndStateTests: XCTestCase {

    // Every `make` uses a fresh random salt, so the cheap iteration count here is only about
    // keeping the suite fast -- it doesn't weaken what's being asserted.
    private let testIterations = 1_000

    private func record(_ passcode: String) -> PasscodeRecord {
        guard let record = PasscodeHash.make(passcode: passcode, iterations: testIterations) else {
            fatalError("PasscodeHash.make returned nil")
        }
        return record
    }

    // MARK: - Passcode verification

    func testVerifyAcceptsCorrectPasscode() {
        let stored = record("correct horse battery")
        XCTAssertTrue(PasscodeHash.verify(passcode: "correct horse battery", against: stored))
    }

    func testVerifyRejectsWrongPasscode() {
        let stored = record("correct horse battery")
        XCTAssertFalse(PasscodeHash.verify(passcode: "correct horse batterz", against: stored))
        XCTAssertFalse(PasscodeHash.verify(passcode: "", against: stored))
        XCTAssertFalse(PasscodeHash.verify(passcode: "correct horse battery ", against: stored))
    }

    func testSamePasscodeGetsDistinctSalts() {
        // Equal digests for equal passcodes would mean an unsalted hash -- and would leak, to
        // anyone who got two state files, that both machines use the same passcode.
        XCTAssertNotEqual(record("hunter2hunter2").hashBase64, record("hunter2hunter2").hashBase64)
    }

    func testVerifyRejectsCorruptRecord() {
        let stored = record("hunter2hunter2")
        let corrupt = PasscodeRecord(
            saltBase64: "not base64 at all!!",
            hashBase64: stored.hashBase64,
            iterations: stored.iterations
        )
        XCTAssertFalse(PasscodeHash.verify(passcode: "hunter2hunter2", against: corrupt))
    }

    // MARK: - Redaction

    func testRedactedForStatusStripsDigestButReportsConfigured() {
        let state = FocusLockState(guardianPasscode: record("hunter2hunter2"))
        let redacted = state.redactedForStatus()

        XCTAssertNil(redacted.guardianPasscode, "the digest must never leave the daemon")
        XCTAssertTrue(redacted.passcodeConfigured)
    }

    func testRedactedForStatusReportsUnconfiguredWhenNoPasscode() {
        let redacted = FocusLockState().redactedForStatus()
        XCTAssertNil(redacted.guardianPasscode)
        XCTAssertFalse(redacted.passcodeConfigured)
    }

    /// The actual wire path: redact, encode, decode on the client. The client has no digest to
    /// derive `passcodeConfigured` from, so it has to survive as its own field.
    func testPasscodeConfiguredSurvivesTheWireWithoutTheDigest() {
        let state = FocusLockState(guardianPasscode: record("hunter2hunter2"))
        let encoded = FocusLockCodec.encode(state.redactedForStatus())

        // Belt and braces: the digest must not appear anywhere in the payload's bytes.
        let json = String(data: encoded, encoding: .utf8) ?? ""
        XCTAssertFalse(json.contains(state.guardianPasscode!.hashBase64))
        XCTAssertFalse(json.contains(state.guardianPasscode!.saltBase64))

        let decoded = FocusLockCodec.decode(FocusLockState.self, from: encoded)
        XCTAssertEqual(decoded?.passcodeConfigured, true)
        XCTAssertNil(decoded?.guardianPasscode)
    }

    // MARK: - Persistence

    /// The other half of the split: the *unredacted* encode (what StateStore writes) has to keep
    /// the digest, or a daemon restart would silently drop the passcode and reopen the gate.
    func testUnredactedRoundTripPreservesPasscode() {
        let original = FocusLockState(guardianPasscode: record("hunter2hunter2"))
        let decoded = FocusLockCodec.decode(FocusLockState.self, from: FocusLockCodec.encode(original))

        XCTAssertEqual(decoded?.guardianPasscode, original.guardianPasscode)
        XCTAssertTrue(PasscodeHash.verify(passcode: "hunter2hunter2", against: decoded!.guardianPasscode!))
    }

    /// A state.json written by a build that predates the passcode/pendingActions/cooldown fields
    /// (all now removed, but an existing install's on-disk file may still carry their old JSON
    /// keys) must still decode cleanly and keep existing blocks -- Codable ignores keys with no
    /// matching property rather than failing the whole decode.
    func testLegacyStateDecodesCleanlyWithNoPasscode() {
        let legacy = """
        {
          "blockedApps": [],
          "blockedDomains": ["example.com"],
          "protectedApps": [],
          "dnsEnforcementEnabled": true,
          "cloudFilterHost": "vpn.bartholomew.help",
          "cloudFilterEnabled": true,
          "lockProfileInstalled": false,
          "cooldownHours": 24,
          "pendingActions": []
        }
        """.data(using: .utf8)!

        let decoded = FocusLockCodec.decode(FocusLockState.self, from: legacy)
        XCTAssertNil(decoded?.guardianPasscode)
        XCTAssertFalse(decoded?.passcodeConfigured ?? true)
        XCTAssertEqual(decoded?.blockedDomains, ["example.com"], "existing blocks must survive the upgrade")
    }
}
