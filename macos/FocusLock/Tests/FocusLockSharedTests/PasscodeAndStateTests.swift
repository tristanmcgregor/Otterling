import XCTest
@testable import FocusLockShared

/// These cover the failure modes that wouldn't show up as a crash or a visibly broken app: a
/// passcode digest leaking to unprivileged callers, a passcode silently vanishing across a save,
/// or an upgrade quietly landing on a zero-length cooldown.
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

    func testPendingActionsRoundTrip() {
        let action = PendingAction(
            kind: .removeBlockedDomain,
            target: "reddit.com",
            requestedAt: Date(timeIntervalSince1970: 1_700_000_000),
            effectiveAt: Date(timeIntervalSince1970: 1_700_086_400)
        )
        let state = FocusLockState(pendingActions: [action])
        let decoded = FocusLockCodec.decode(FocusLockState.self, from: FocusLockCodec.encode(state))

        XCTAssertEqual(decoded?.pendingActions.count, 1)
        XCTAssertEqual(decoded?.pendingActions.first?.id, action.id)
        XCTAssertEqual(decoded?.pendingActions.first?.kind, .removeBlockedDomain)
        XCTAssertEqual(decoded?.pendingActions.first?.target, "reddit.com")
    }

    /// A state.json written by a build that predates all of this must not decode into "no cooldown"
    /// -- that would hand every upgrading install instant removals, the exact thing the field exists
    /// to prevent.
    func testLegacyStateDecodesWithDefaultCooldownAndNoPasscode() {
        let legacy = """
        {
          "blockedApps": [],
          "blockedDomains": ["example.com"],
          "protectedApps": [],
          "dnsEnforcementEnabled": true,
          "cloudFilterHost": "vpn.bartholomew.help",
          "cloudFilterEnabled": true,
          "lockProfileInstalled": false
        }
        """.data(using: .utf8)!

        let decoded = FocusLockCodec.decode(FocusLockState.self, from: legacy)
        XCTAssertEqual(decoded?.cooldownHours, FocusLockConstants.defaultCooldownHours)
        XCTAssertNil(decoded?.guardianPasscode)
        XCTAssertFalse(decoded?.passcodeConfigured ?? true)
        XCTAssertEqual(decoded?.pendingActions.count, 0)
        XCTAssertEqual(decoded?.blockedDomains, ["example.com"], "existing blocks must survive the upgrade")
    }

    // MARK: - Cooldown maturity

    func testIsMatureOnlyAfterEffectiveAt() {
        let now = Date()
        let action = PendingAction(
            kind: .disableDNSEnforcement,
            target: "",
            requestedAt: now,
            effectiveAt: now.addingTimeInterval(3600)
        )
        XCTAssertFalse(action.isMature(asOf: now))
        XCTAssertFalse(action.isMature(asOf: now.addingTimeInterval(3599)))
        XCTAssertTrue(action.isMature(asOf: now.addingTimeInterval(3601)))
    }

    func testDescribedFullyOmitsEmptyTarget() {
        let now = Date()
        let noTarget = PendingAction(kind: .disableDNSEnforcement, target: "", requestedAt: now, effectiveAt: now)
        let withTarget = PendingAction(kind: .removeBlockedDomain, target: "reddit.com", requestedAt: now, effectiveAt: now)

        XCTAssertEqual(noTarget.describedFully, "Turn off DNS enforcement")
        XCTAssertEqual(withTarget.describedFully, "Unblock site reddit.com")
    }
}
