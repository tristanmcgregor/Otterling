import Foundation
import FocusLockShared

/// The GUI's "AI Assistant" chat box, e.g. typing "install wget" instead of a raw shell command --
/// see `lockprofile_service.py`'s `/ai-assistant/translate` doc comment for the server side.
///
/// IMPORTANT: this is a convenience layer over `SudoBroker`, not a second way to run commands.
/// `translate()` below only turns natural language into candidate shell command(s) -- it does not
/// execute anything and reasons about nothing except "what command(s) would accomplish this." Every
/// command it returns is then run through `SudoBroker.handle()` individually by
/// `XPCService.requestAssistantAction`, exactly like a manually-typed command in the terminal
/// screen. There is deliberately no path from "the assistant said this is fine" straight to
/// execution -- see the design discussion this followed: an agent trusted to both interpret intent
/// and decide safety is the same single point of failure the broker exists to avoid, just wearing a
/// friendlier interface.
///
/// `XPCService.requestAssistantAction` calls `translate()` in a loop -- after each round's commands
/// run, their real stdout/stderr/exit codes are folded into the next `translate()` call so the
/// assistant can adapt (retry a narrower command, chain a follow-up step, notice a denial and stop)
/// instead of guessing everything up front from a single sentence. This makes the *front end* feel
/// like an agent working a multi-step task. It changes nothing about the invariant above: each
/// round is still pure translation with no execution authority, and every command from every round
/// still goes through the exact same `SudoBroker.handle()` a manually-typed command does. The loop
/// itself is capped (`XPCService`'s `maxAssistantRounds`/`maxAssistantSteps`) independent of
/// anything the translator says, since nothing else stops a propose-execute-observe cycle from
/// looping forever -- e.g. re-proposing a command the broker just denied, hoping a differently
/// worded round talks its way past the same denylist entry. It can't: the denylist/allowlist/
/// AI-review decision is re-evaluated fresh every time, with no memory of "the assistant already
/// decided this was fine."
enum AIAssistantClient {
    private static let timeout: TimeInterval = 25

    /// Returns the candidate commands (empty on any failure/ambiguity -- never fabricates a
    /// command when the round-trip fails) and a short explanation to show the Guardian.
    static func translate(request: String) -> (commands: [String], explanation: String) {
        let host = nonEmpty(readTrimmed(FocusLockConstants.lockProfileHostPath))
            ?? FocusLockConstants.defaultLockProfileHost
        let token = nonEmpty(readTrimmed(FocusLockConstants.lockProfileTokenPath))
            ?? FocusLockConstants.defaultLockProfileToken
        guard !host.isEmpty, !token.isEmpty, let url = URL(string: "https://\(host)/ai-assistant/translate") else {
            return ([], "Assistant is not reachable (no host/token provisioned).")
        }

        guard let payload = try? JSONSerialization.data(withJSONObject: ["request": request]) else {
            return ([], "Failed to encode assistant request.")
        }

        var urlRequest = URLRequest(url: url, timeoutInterval: timeout)
        urlRequest.httpMethod = "POST"
        urlRequest.httpBody = payload
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let semaphore = DispatchSemaphore(value: 0)
        var result: (commands: [String], explanation: String) = ([], "Assistant request timed out or the server was unreachable.")
        URLSession.shared.dataTask(with: urlRequest) { data, response, error in
            defer { semaphore.signal() }
            guard error == nil,
                  let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data,
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return
            }
            let commands = (parsed["commands"] as? [String]) ?? []
            let explanation = (parsed["explanation"] as? String) ?? ""
            result = (commands, explanation)
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
