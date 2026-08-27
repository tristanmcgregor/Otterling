import Foundation

/// Uploads a captured foreground-app screenshot to filter-server's `/screenshot-classify` (see
/// `filter-server/lockprofile_service.py` and `nsfw_image_classifier.py`) for server-side NSFW
/// classification -- the exact same route and JSON shape the Android app's `ScreenshotUploader.kt`
/// already uses. `device_id`/`package_name`/`image_base64` are platform-agnostic server-side, so
/// nothing on the filter-server needed to change for a Mac to use this route too.
///
/// See `FocusLockScanner/ScreenshotMonitor.swift`'s doc comment for why capture/upload happens
/// from the per-user `FocusLockScanner` LaunchAgent rather than the root `FocusLockHelperd` daemon.
public enum ScreenshotUploader {
    public struct ClassifyResult {
        /// One of "safe", "nsfw", "skipped" (guardian disabled `visualFilterEnabled` server-side),
        /// or "error" (classifier unavailable) -- see `nsfw_image_classifier.py`. Callers must treat
        /// anything other than "nsfw" as non-blocking, matching the phone's contract exactly.
        public let classification: String
        public let blockUntilMillis: Double?
    }

    public enum UploadError: Error {
        case notConfigured
        case invalidResponse
    }

    public static func upload(
        deviceID: String,
        packageName: String,
        imageData: Data,
        completion: @escaping (Result<ClassifyResult, Error>) -> Void
    ) {
        let host = TamperReporter.resolvedHost()
        let token = TamperReporter.resolvedToken()
        guard !host.isEmpty, !token.isEmpty, let url = URL(string: "https://\(host)/screenshot-classify") else {
            completion(.failure(UploadError.notConfigured))
            return
        }

        let body: [String: Any] = [
            "device_id": deviceID,
            "package_name": packageName,
            "image_base64": imageData.base64EncodedString(),
        ]
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            completion(.failure(UploadError.invalidResponse))
            return
        }

        var request = URLRequest(url: url, timeoutInterval: 60)
        request.httpMethod = "POST"
        request.httpBody = payload
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(error))
                return
            }
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let classification = json["classification"] as? String else {
                completion(.failure(UploadError.invalidResponse))
                return
            }
            let blockUntilMillis = json["blockUntilMillis"] as? Double
            completion(.success(ClassifyResult(classification: classification, blockUntilMillis: blockUntilMillis)))
        }.resume()
    }
}
