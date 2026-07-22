import Foundation
import FocusLockShared

/// RSA-OAEP/SHA-256 keypair for claiming a one-time Guardian setup link (see
/// `GuardianAccountManager` and `server/guardian_relay.py`). The private key never leaves this
/// root-owned file; only the public key (X.509 SubjectPublicKeyInfo DER, so a browser's WebCrypto
/// `importKey('spki', ...)` can use it directly) is ever handed out, via
/// `focuslockctl guardian-pubkey`.
///
/// This exists specifically so a relay server administered by the same person the Guardian model
/// is protecting against (i.e. you) never sees a plaintext password: the Guardian's browser
/// encrypts against this public key, and only this file's private key -- root-owned, exactly as
/// protected as everything else in the Guardian model -- can decrypt the result.
///
/// Deliberately shells out to `/usr/bin/openssl` rather than using the Security framework's
/// SecKey APIs: those internally talk to securityd for a keychain session, which a headless
/// LaunchDaemon (no user session attached) never has, and the calls hang indefinitely instead of
/// failing. openssl's CLI operates on plain key files with no keychain/session involved, which is
/// exactly the trust model root-owned files already have here.
enum GuardianSetupCrypto {
    private static let opensslPath = "/usr/bin/openssl"
    private static let privateKeyPath = "\(FocusLockConstants.stateDirectory)/guardian_setup_priv.pem"
    private static let keySizeInBits = 2048

    static func publicKeySPKIBase64() -> String? {
        guard ensurePrivateKeyExists() else { return nil }
        guard let der = run(opensslPath, ["pkey", "-in", privateKeyPath, "-pubout", "-outform", "DER"]) else {
            return nil
        }
        return der.base64EncodedString()
    }

    /// Returns nil on any failure (malformed base64, decrypt failure, non-UTF8 plaintext) --
    /// deliberately doesn't distinguish which, so callers can't be used as a decryption oracle.
    static func decrypt(base64Ciphertext: String) -> String? {
        guard ensurePrivateKeyExists() else { return nil }
        guard let cipherData = Data(base64Encoded: base64Ciphertext) else { return nil }
        guard let plainData = run(
            opensslPath,
            [
                "pkeyutl", "-decrypt",
                "-inkey", privateKeyPath,
                "-pkeyopt", "rsa_padding_mode:oaep",
                "-pkeyopt", "rsa_oaep_md:sha256",
                "-pkeyopt", "rsa_mgf1_md:sha256",
            ],
            stdin: cipherData
        ) else {
            return nil
        }
        return String(data: plainData, encoding: .utf8)
    }

    @discardableResult
    private static func ensurePrivateKeyExists() -> Bool {
        if FileManager.default.fileExists(atPath: privateKeyPath) { return true }

        try? FileManager.default.createDirectory(
            atPath: FocusLockConstants.stateDirectory,
            withIntermediateDirectories: true
        )
        guard run(opensslPath, ["genrsa", "-out", privateKeyPath, "\(keySizeInBits)"]) != nil else {
            return false
        }
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: privateKeyPath)
        return FileManager.default.fileExists(atPath: privateKeyPath)
    }

    /// Runs a command, optionally feeding `stdin` to it, and returns stdout data on success (exit
    /// code 0) or nil otherwise. Never surfaces stderr to logs -- some of these calls handle a
    /// decrypted secret in-process (not on the command line, so it never shows up in `ps`), and
    /// there's no reason to risk it leaking into diagnostic output.
    private static func run(_ path: String, _ args: [String], stdin: Data? = nil) -> Data? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args

        let outputPipe = Pipe()
        process.standardOutput = outputPipe
        process.standardError = FileHandle.nullDevice

        if let stdin {
            let inputPipe = Pipe()
            process.standardInput = inputPipe
            guard (try? process.run()) != nil else { return nil }
            inputPipe.fileHandleForWriting.write(stdin)
            try? inputPipe.fileHandleForWriting.close()
        } else {
            process.standardInput = FileHandle.nullDevice
            guard (try? process.run()) != nil else { return nil }
        }

        let outputData = outputPipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        return outputData
    }
}
