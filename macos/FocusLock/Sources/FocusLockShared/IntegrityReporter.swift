import Foundation

/// Reports this build's provenance (`BuildProvenance`) to the filter-server's `/integrity/checkin`
/// endpoint on every daemon start and periodically thereafter -- the answer to "what's stopping me
/// from editing the code and installing it locally instead of going through my normal process."
///
/// This is deliberately a REPORTING mechanism, not a gate: nothing in this app's enforcement path
/// waits on a response from this call, and a network outage never blocks anything. Making the app's
/// own function depend on a server round-trip would violate the project's hard fail-open rule (see
/// `ProxyEnforcer`'s doc comment) -- a legitimate, un-tampered Mac with no internet would otherwise
/// either brick itself or the check would have to be quietly skippable, which a tampered build could
/// exploit just as easily. Instead: report the build's git commit and whether the working tree was
/// dirty at build time, and let the SERVER -- not this binary -- decide whether to alert the
/// accountability partner. A tampered binary could patch this call out entirely, but then it simply
/// stops checking in, which is its own signal (see `lockprofile_service.py`'s `/integrity/checkin`
/// doc comment for what's actually verified and its known limits).
public enum IntegrityReporter {
    public static func checkIn() {
        guard let provenance = BuildProvenance.current() else { return }

        let host = nonEmpty(readTrimmed(FocusLockConstants.lockProfileHostPath))
            ?? FocusLockConstants.defaultLockProfileHost
        let token = nonEmpty(readTrimmed(FocusLockConstants.lockProfileTokenPath))
            ?? FocusLockConstants.defaultLockProfileToken
        guard !host.isEmpty, !token.isEmpty else { return }
        guard let url = URL(string: "https://\(host)/integrity/checkin") else { return }

        let body: [String: Any] = [
            "device_id": TamperReporter.deviceID() ?? "unknown",
            "device_name": TamperReporter.computerName() ?? "",
            "git_sha": provenance.gitSha,
            "dirty": provenance.dirty,
            "built_at": provenance.builtAt,
            "ts": Date().timeIntervalSince1970,
        ]
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else { return }

        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.httpBody = payload
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        URLSession.shared.dataTask(with: request) { _, response, error in
            guard error != nil || (response as? HTTPURLResponse)?.statusCode != 200 else { return }
            FileHandle.standardError.write(
                "[IntegrityReporter] check-in failed: \(error?.localizedDescription ?? "non-200 response")\n"
                    .data(using: .utf8)!
            )
        }.resume()
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
