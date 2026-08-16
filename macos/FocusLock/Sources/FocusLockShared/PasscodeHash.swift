import CommonCrypto
import Foundation
import Security

/// A salted PBKDF2-SHA256 digest of the Guardian passcode. Only ever persisted inside the
/// root-owned `state.json`, and deliberately stripped out of every `getStatus` reply (see
/// `FocusLockState.passcodeConfigured`) so a caller that can read status still can't take the
/// digest away and grind it offline.
///
/// The passcode -- not `admin` group membership -- is the authorization boundary once one is set.
/// That's the whole point of this type: under the single-admin model the daily account *is* in
/// `admin`, so `AdminGroupCheck` returns true for the very person the gate is meant to slow down.
/// A secret the machine's user doesn't hold is the only thing left that a local check can't be
/// talked out of. See `XPCService.authorize`.
public struct PasscodeRecord: Codable, Hashable, Sendable {
    public let saltBase64: String
    public let hashBase64: String
    public let iterations: Int

    public init(saltBase64: String, hashBase64: String, iterations: Int) {
        self.saltBase64 = saltBase64
        self.hashBase64 = hashBase64
        self.iterations = iterations
    }
}

public enum PasscodeHash {
    /// Cost is bounded by what a daemon can absorb on a single XPC call, not by a login flow's
    /// latency budget -- removals are rare and deliberately slow anyway, so this errs high.
    public static let defaultIterations = 210_000
    private static let keyLength = 32
    private static let saltLength = 32

    /// Nil only if the system RNG or the KDF itself fails -- callers must treat that as "could not
    /// set a passcode" and leave any existing record untouched rather than falling back to a
    /// weaker derivation.
    public static func make(passcode: String, iterations: Int = defaultIterations) -> PasscodeRecord? {
        var salt = Data(count: saltLength)
        let generated = salt.withUnsafeMutableBytes { buffer -> Int32 in
            guard let base = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, saltLength, base)
        }
        guard generated == errSecSuccess else { return nil }
        guard let derived = derive(passcode: passcode, salt: salt, iterations: iterations) else { return nil }
        return PasscodeRecord(
            saltBase64: salt.base64EncodedString(),
            hashBase64: derived.base64EncodedString(),
            iterations: iterations
        )
    }

    public static func verify(passcode: String, against record: PasscodeRecord) -> Bool {
        guard let salt = Data(base64Encoded: record.saltBase64),
              let expected = Data(base64Encoded: record.hashBase64),
              let derived = derive(passcode: passcode, salt: salt, iterations: record.iterations)
        else { return false }
        return constantTimeEquals(derived, expected)
    }

    private static func derive(passcode: String, salt: Data, iterations: Int) -> Data? {
        var derived = Data(count: keyLength)
        let status = derived.withUnsafeMutableBytes { derivedBuffer -> Int32 in
            salt.withUnsafeBytes { saltBuffer -> Int32 in
                guard let derivedBase = derivedBuffer.bindMemory(to: UInt8.self).baseAddress,
                      let saltBase = saltBuffer.bindMemory(to: UInt8.self).baseAddress
                else { return Int32(kCCParamError) }
                return CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    passcode,
                    passcode.utf8.count,
                    saltBase,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    derivedBase,
                    keyLength
                )
            }
        }
        guard status == Int32(kCCSuccess) else { return nil }
        return derived
    }

    /// Compares every byte regardless of where the first mismatch is, so the reply latency of a
    /// failed unlock doesn't leak how much of a guess was correct.
    private static func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for (left, right) in zip(lhs, rhs) {
            difference |= left ^ right
        }
        return difference == 0
    }
}
