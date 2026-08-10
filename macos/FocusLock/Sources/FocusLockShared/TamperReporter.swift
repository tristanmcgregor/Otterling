import Foundation

/// Best-effort POST of a tamper event to the filter-server's `/alerts/tamper` ingestion endpoint
/// (see `filter-server/lockprofile_service.py`). Ingestion only for now -- no outbound
/// notification is wired up on the server side yet, so this is a paper trail the Guardian can go
/// look at, not an alert that reaches them proactively. Never blocks or throws into its caller.
///
/// Lives in `FocusLockShared` (not `FocusLockHelperd`) because both the main daemon
/// (`LockProfileGuard`) and the independent watchdog LaunchDaemon (`FocusLockWatchdog`) need to
/// call it, and those are separate executable targets/processes -- this is the one piece of logic
/// they share beyond the XPC protocol itself.
public enum TamperReporter {
    /// Fire-and-forget with a single retry. Silently does nothing if `install_lock_profile.py`
    /// was never run (no saved host/token) -- that's a valid, expected state for any install that
    /// hasn't set up the lock profile yet, not an error.
    public static func report(type: String, details: String) {
        guard let host = readTrimmed(FocusLockConstants.lockProfileHostPath),
              let token = readTrimmed(FocusLockConstants.lockProfileTokenPath),
              !host.isEmpty, !token.isEmpty else {
            return
        }
        guard let url = URL(string: "https://\(host)/alerts/tamper") else { return }
        let body: [String: Any] = [
            "device_id": deviceID() ?? "unknown",
            "type": type,
            "details": details,
            "ts": Date().timeIntervalSince1970,
        ]
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else { return }

        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.httpBody = payload
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        send(request, retriesLeft: 1)
    }

    private static func send(_ request: URLRequest, retriesLeft: Int) {
        URLSession.shared.dataTask(with: request) { _, response, error in
            let succeeded = error == nil && (response as? HTTPURLResponse).map { $0.statusCode == 200 } == true
            if !succeeded, retriesLeft > 0 {
                DispatchQueue.global().asyncAfter(deadline: .now() + 5) {
                    send(request, retriesLeft: retriesLeft - 1)
                }
            } else if !succeeded {
                FileHandle.standardError.write(
                    "[TamperReporter] failed to report tamper event: \(error?.localizedDescription ?? "non-200 response")\n".data(using: .utf8)!
                )
            }
        }.resume()
    }

    /// Same `IOPlatformUUID` this device is provisioned under server-side by
    /// `install_lock_profile.py`'s `device_id()` -- must match exactly for tamper events to
    /// correlate with the right device.
    private static func deviceID() -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/ioreg")
        process.arguments = ["-rd1", "-c", "IOPlatformExpertDevice"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard let output = String(data: data, encoding: .utf8) else { return nil }
        for line in output.split(separator: "\n") where line.contains("IOPlatformUUID") {
            guard let equalsIndex = line.firstIndex(of: "=") else { continue }
            let value = line[line.index(after: equalsIndex)...]
                .trimmingCharacters(in: .whitespaces)
                .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            return value
        }
        return nil
    }

    private static func readTrimmed(_ path: String) -> String? {
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
