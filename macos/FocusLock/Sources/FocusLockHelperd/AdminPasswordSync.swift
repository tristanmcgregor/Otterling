import Foundation
import FocusLockShared

/// Polls `GET /dashboard-api/admin-password-sync` (see lockprofile_service.py's
/// ADMIN_PASSWORD_SYNC_PATH doc comment and route_policy.py's DEVICE_BEARER_ROUTES entry for the
/// full security tradeoff this accepts) and, when a PIN is pending, applies it as the local
/// `admin-account` macOS user's login password via `dscl`. Only ever fires right after the
/// one-time account-handoff link (see the dashboard's "Account handoff" section) is actually
/// used to set a new Guardian PIN -- not on every ordinary PIN change.
///
/// Explicitly, deliberately NOT the same pattern DashboardConfigSync.swift's header comment
/// documents for `guardianPasscode` (rejected there as this Mac's own removal credential) -- this
/// is a different, narrower, explicitly-requested feature: syncing the handoff PIN specifically
/// onto one named local macOS account, not using the Guardian PIN as a general local credential.
///
/// Host/token resolution and the fire-and-forget stderr logging style copy DashboardConfigSync.swift
/// exactly. The fetched PIN is held in memory only for the few `dscl` calls below and is never
/// written to disk or logged.
enum AdminPasswordSync {
    private static let timeout: TimeInterval = 15

    /// The local macOS account this feature is scoped to -- see this file's header comment. Not
    /// derived from anything server-side; a Mac with no such account just logs and does nothing.
    private static let targetAccountName = "Admin"

    static func check() {
        let host = nonEmpty(readTrimmed(FocusLockConstants.lockProfileHostPath))
            ?? FocusLockConstants.defaultLockProfileHost
        let token = nonEmpty(readTrimmed(FocusLockConstants.lockProfileTokenPath))
            ?? FocusLockConstants.defaultLockProfileToken
        guard !host.isEmpty, !token.isEmpty,
              let url = URL(string: "https://\(host)/dashboard-api/admin-password-sync") else {
            return
        }

        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        URLSession.shared.dataTask(with: request) { data, response, error in
            guard error == nil,
                  let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data else {
                // Not logged as a failure -- this route is polled continuously and "nothing
                // pending" is by far the common case, same non-noisy stance as a failed poll
                // anywhere else in this file's sibling syncs.
                return
            }
            guard let raw = try? JSONDecoder().decode(Response.self, from: data),
                  let pin = raw.pin else {
                return
            }
            // Re-validated here rather than trusted from the response shape, same posture
            // DashboardConfigSync.swift takes on every other server-supplied value this daemon
            // acts on -- the server already enforces this format, but this daemon runs as root
            // and is about to shell out, so it checks for itself too.
            guard pin.count == 4, pin.allSatisfy({ $0.isNumber }) else {
                FileHandle.standardError.write(
                    "[admin-password-sync] received a pending PIN in an unexpected format -- refusing to apply it\n".data(using: .utf8)!
                )
                return
            }
            applyLocally(pin: pin)
        }.resume()
    }

    /// The server has already discarded its own copy of `pin` by the time this response reached
    /// us (single-use, see ADMIN_PASSWORD_SYNC_PATH's comment) -- so this is the one and only
    /// chance to apply it locally. A couple of local retries on `dscl` failure make sense (a
    /// transient directory-service hiccup shouldn't burn the one shot), but there is nowhere to
    /// re-fetch from if these all fail.
    private static func applyLocally(pin: String) {
        guard accountExists(targetAccountName) else {
            FileHandle.standardError.write(
                "[admin-password-sync] pending PIN received, but no local account named '\(targetAccountName)' exists on this Mac -- skipped\n".data(using: .utf8)!
            )
            return
        }
        for attempt in 1...3 {
            if runDscl(["-passwd", "/Users/\(targetAccountName)", pin]) {
                FileHandle.standardError.write(
                    "[admin-password-sync] applied the handoff PIN as \(targetAccountName)'s local login password\n".data(using: .utf8)!
                )
                return
            }
            FileHandle.standardError.write(
                "[admin-password-sync] dscl attempt \(attempt)/3 failed\n".data(using: .utf8)!
            )
        }
        FileHandle.standardError.write(
            "[admin-password-sync] gave up applying the handoff PIN to \(targetAccountName) after 3 attempts -- it will need to be set by hand\n".data(using: .utf8)!
        )
    }

    private static func accountExists(_ name: String) -> Bool {
        runDscl(["-read", "/Users/\(name)"])
    }

    /// As root (this daemon's own LaunchDaemon runs as root -- see HELPER_LABEL's plist in
    /// build_app.sh), `dscl . -passwd /Users/<name> <newPassword>` sets that account's password
    /// directly, without needing to know the current one -- unlike a non-root caller changing
    /// their own password with the same flag. Never logs `arguments` itself; only exit status.
    @discardableResult
    private static func runDscl(_ arguments: [String]) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/dscl")
        process.arguments = ["."] + arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
            process.waitUntilExit()
            return process.terminationStatus == 0
        } catch {
            return false
        }
    }

    private struct Response: Decodable {
        let pin: String?
    }

    private static func readTrimmed(_ path: String) -> String? {
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
