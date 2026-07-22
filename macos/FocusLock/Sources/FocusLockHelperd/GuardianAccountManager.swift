import Foundation

/// Creates the Guardian admin account (if it doesn't exist yet) or resets its password (if it
/// does), from a plaintext password that only ever exists in memory for the duration of this call
/// -- decrypted by `GuardianSetupCrypto` immediately beforehand and never logged. Root can set or
/// reset any local account's password without knowing the previous one, which is exactly what
/// lets this run unattended from a Standard account's request without that account ever learning
/// the result.
enum GuardianAccountManager {
    static let accountName = "Guardian"

    static func applyPassword(_ password: String) -> Bool {
        guard !password.isEmpty else { return false }
        if accountExists() {
            let status = run("/usr/sbin/sysadminctl", ["-resetPasswordFor", accountName, "-newPassword", password])
            return status == 0
        } else {
            let status = run(
                "/usr/sbin/sysadminctl",
                ["-addUser", accountName, "-fullName", accountName, "-password", password, "-admin"]
            )
            return status == 0
        }
    }

    private static func accountExists() -> Bool {
        run("/usr/bin/dscl", [".", "-read", "/Users/\(accountName)"]) == 0
    }

    /// Deliberately discards stdout/stderr -- a failed `sysadminctl -addUser`/`-resetPasswordFor`
    /// doesn't echo the password back, but there's no reason to keep any output from a command
    /// that was just handed a secret around longer than necessary.
    @discardableResult
    private static func run(_ path: String, _ args: [String]) -> Int32 {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = args
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return -1 }
        process.waitUntilExit()
        return process.terminationStatus
    }
}
