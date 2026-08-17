import Foundation
import FocusLockShared

/// The tier-3 fallback in `SudoBroker`'s decision pipeline: a synchronous round-trip to the
/// filter-server's `/sudo-review/check` endpoint, which asks an AI reviewer to judge a command
/// `SudoBroker`'s own local denylist/allowlist didn't already resolve.
///
/// Deliberately synchronous (blocks the calling XPC handler thread) -- the Guardian is waiting live
/// for a decision on a command they want to run right now, unlike the daemon's other network calls
/// (TamperReporter, IntegrityReporter), which are fire-and-forget background reporting no one is
/// blocked on.
///
/// FAIL-CLOSED: this is the one place in this app that inverts the fail-open rule used everywhere
/// else (DNS, proxy). Denying an admin command on a network hiccup is safe and recoverable; treating
/// an unreachable reviewer as "approved" would make cutting network access the master bypass for
/// the entire broker. Every failure path below returns `approved: false`.
enum AIReviewClient {
    private static let timeout: TimeInterval = 20

    static func review(command: String, reason: String) -> ElevatedCommandResult {
        let host = nonEmpty(readTrimmed(FocusLockConstants.lockProfileHostPath))
            ?? FocusLockConstants.defaultLockProfileHost
        let token = nonEmpty(readTrimmed(FocusLockConstants.lockProfileTokenPath))
            ?? FocusLockConstants.defaultLockProfileToken
        guard !host.isEmpty, !token.isEmpty, let url = URL(string: "https://\(host)/sudo-review/check") else {
            return ElevatedCommandResult(
                approved: false, source: "error",
                explanation: "AI review is not reachable (no host/token provisioned) -- denying, not approving, on this failure."
            )
        }

        let body: [String: Any] = [
            "device_id": TamperReporter.deviceID() ?? "unknown",
            "device_name": TamperReporter.computerName() ?? "",
            "command": command,
            "reason": reason,
        ]
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            return ElevatedCommandResult(approved: false, source: "error", explanation: "Failed to encode review request.")
        }

        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.httpBody = payload
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let semaphore = DispatchSemaphore(value: 0)
        var result = ElevatedCommandResult(
            approved: false, source: "error",
            explanation: "AI review request timed out or the server was unreachable -- denying on this failure."
        )
        URLSession.shared.dataTask(with: request) { data, response, error in
            defer { semaphore.signal() }
            guard error == nil,
                  let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data,
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return
            }
            let verdict = (parsed["verdict"] as? String)?.lowercased()
            let explanation = (parsed["explanation"] as? String) ?? "(no explanation returned)"
            switch verdict {
            case "allow":
                result = ElevatedCommandResult(approved: true, source: "ai_review", explanation: explanation)
            case "deny":
                result = ElevatedCommandResult(approved: false, source: "ai_review", explanation: explanation)
            default:
                // Any verdict string other than an exact "allow" -- including "unsure", missing,
                // or malformed -- is a deny. Never interpret ambiguity as permission.
                result = ElevatedCommandResult(
                    approved: false, source: "ai_review",
                    explanation: "Reviewer returned an unrecognized/ambiguous verdict ('\(verdict ?? "nil")') -- denying on ambiguity. \(explanation)"
                )
            }
        }.resume()
        _ = semaphore.wait(timeout: .now() + timeout + 2)
        return result
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
