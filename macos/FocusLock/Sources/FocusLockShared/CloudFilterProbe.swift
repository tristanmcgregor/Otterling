import Foundation
import Network

/// Sends a throwaway UDP DNS query straight to a candidate cloud filter host and reports whether
/// any reply came back within the timeout -- mirrors the Android app's
/// `CloudFilterSettings.testReachable()`. Runs directly in the calling (unprivileged) process:
/// this is a read-only network probe, not a state mutation, so it doesn't need to go through the
/// daemon's XPC surface at all.
public enum CloudFilterProbe {
    public static func testReachable(host: String, timeoutSeconds: Double = 3) async -> Bool {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }

        return await withCheckedContinuation { continuation in
            let lock = NSLock()
            var resumed = false
            let connection = NWConnection(host: NWEndpoint.Host(trimmed), port: 53, using: .udp)
            let finish: (Bool) -> Void = { ok in
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                connection.cancel()
                continuation.resume(returning: ok)
            }

            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    connection.send(content: buildQuery(name: "example.com"), completion: .contentProcessed { _ in })
                    connection.receiveMessage { data, _, _, error in
                        finish(data != nil && error == nil)
                    }
                case .failed, .cancelled:
                    finish(false)
                default:
                    break
                }
            }
            connection.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeoutSeconds) { finish(false) }
        }
    }

    /// Minimal standalone "A" query -- only used by this probe; real DNS traffic is handled
    /// entirely by the OS resolver once system DNS points at the configured host.
    private static func buildQuery(name: String) -> Data {
        var bytes: [UInt8] = [
            0x12, 0x34, // arbitrary transaction ID
            0x01, 0x00, // flags: standard query, recursion desired
            0x00, 0x01, // QDCOUNT = 1
            0x00, 0x00, // ANCOUNT
            0x00, 0x00, // NSCOUNT
            0x00, 0x00, // ARCOUNT
        ]
        for label in name.split(separator: ".") {
            bytes.append(UInt8(label.utf8.count))
            bytes.append(contentsOf: Array(label.utf8))
        }
        bytes.append(0) // root label
        bytes.append(contentsOf: [0x00, 0x01, 0x00, 0x01]) // QTYPE=A, QCLASS=IN
        return Data(bytes)
    }
}
