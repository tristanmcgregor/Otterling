import Foundation

/// Best-effort POST of a tamper event to the filter-server's `/alerts/tamper` ingestion endpoint
/// (see `filter-server/lockprofile_service.py`), which fans it out to ntfy and the phone's
/// `/alerts/poll` -> SMS relay. Never blocks or throws into its caller.
///
/// Lives in `FocusLockShared` (not `FocusLockHelperd`) because both the main daemon
/// (`LockProfileGuard`) and the independent watchdog LaunchDaemon (`FocusLockWatchdog`) need to
/// call it, and those are separate executable targets/processes -- this is the one piece of logic
/// they share beyond the XPC protocol itself.
public enum TamperReporter {
    /// Minimum spacing between reports of the *same* `type`, so a flapping condition (e.g. DNS
    /// floor disabled/re-enabled in a loop) can't flood ntfy and the phone's SMS relay. Deliberately
    /// short -- this guards against runaway flapping, not against genuinely repeated tamper events
    /// the accountability partner should still see in a timely way.
    private static let minReportInterval: TimeInterval = 300

    /// Fire-and-forget with a single retry. Uses the host/token provisioned by
    /// `install_lock_profile.py` when present, otherwise the baked-in defaults in
    /// `FocusLockConstants` -- so reporting works out of the box on a fresh install.
    public static func report(type: String, details: String) {
        guard shouldSend(type: type) else { return }
        let host = resolvedHost()
        let token = resolvedToken()
        guard !host.isEmpty, !token.isEmpty else { return }
        guard let url = URL(string: "https://\(host)/alerts/tamper") else { return }
        let body: [String: Any] = [
            "device_id": deviceID() ?? "unknown",
            // Human-readable computer name (e.g. "Tristan's MacBook Pro") for the phone's SMS text
            // (see AlertReporter.kt's formatBody) -- device_id above stays the stable opaque key
            // (IOPlatformUUID) server-side records are keyed on; this is display-only.
            "device_name": computerName() ?? "",
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

    /// Checks and updates the shared, file-backed last-sent timestamp for `type`, returning
    /// whether this call is allowed to actually send. File-backed (not an in-memory dict) because
    /// `report` is called from the daemon, the watchdog LaunchDaemon, and the scanner CLI --
    /// separate processes that don't share memory but do all run as root. `flock` serializes
    /// concurrent read-modify-write across all of them; failures fail open (allow the send) so a
    /// rate-limiter bug can't silently swallow a real tamper report.
    private static func shouldSend(type: String) -> Bool {
        FocusLockConstants.ensureStateDirectoryExists()
        let path = FocusLockConstants.tamperReportStatePath
        let fd = open(path, O_RDWR | O_CREAT, 0o600)
        guard fd >= 0 else { return true }
        defer { close(fd) }
        guard flock(fd, LOCK_EX) == 0 else { return true }
        defer { flock(fd, LOCK_UN) }

        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        let data = handle.readDataToEndOfFile()
        var timestamps = (try? JSONDecoder().decode([String: TimeInterval].self, from: data)) ?? [:]

        let now = Date().timeIntervalSince1970
        if let last = timestamps[type], now - last < minReportInterval {
            return false
        }

        timestamps[type] = now
        guard let encoded = try? JSONEncoder().encode(timestamps) else { return true }
        handle.seek(toFileOffset: 0)
        handle.truncateFile(atOffset: 0)
        handle.write(encoded)
        return true
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
    /// correlate with the right device. Public so `IntegrityReporter` reports under the identical
    /// device_id rather than risking a second, divergent implementation.
    public static func deviceID() -> String? {
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

    /// The name shown in System Settings > General > About / Sharing (e.g. "Tristan's MacBook
    /// Pro") -- display-only, see the doc comment where this is used in `report`'s body. Public for
    /// `IntegrityReporter`'s reuse, same reasoning as `deviceID()` above.
    public static func computerName() -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/scutil")
        process.arguments = ["--get", "ComputerName"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Same host/token resolution `report` uses, exposed for `ScreenshotUploader` -- one
    /// implementation of "provisioned file overrides baked-in default", not a second copy that
    /// could drift.
    public static func resolvedHost() -> String {
        nonEmpty(readTrimmed(FocusLockConstants.lockProfileHostPath)) ?? FocusLockConstants.defaultLockProfileHost
    }

    public static func resolvedToken() -> String {
        nonEmpty(readTrimmed(FocusLockConstants.lockProfileTokenPath)) ?? FocusLockConstants.defaultLockProfileToken
    }

    private static func readTrimmed(_ path: String) -> String? {
        guard let data = FileManager.default.contents(atPath: path) else { return nil }
        return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Treats an empty/whitespace-only file the same as a missing one, so a provisioned-but-blank
    /// file falls through to the baked-in default rather than disabling reporting.
    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
