import Foundation

/// Lightweight, independent poll of this Mac's own `visualFilterEnabled`/
/// `visualFilterIntervalSeconds` dashboard settings (see `filter-server/lockprofile_service.py`'s
/// `_default_device_settings`) for `FocusLockScanner`'s `ScreenshotMonitor` -- the Mac equivalent
/// of the phone's `DashboardConfigStore.snapshot()` read in
/// `FocusGuardAccessibilityService.captureScreenshotIfAllowed`/`visualFilterIntervalMillis`.
///
/// Deliberately NOT read from `FocusLockHelperd`'s `DashboardConfigSync`/`StateStore` cache: that
/// state file is root-owned `0600` (see `StateStore.swift`) and this runs in a different, per-user
/// process (`FocusLockScanner`) with no XPC channel to the daemon for this. Polling the same `GET
/// /dashboard-api/devices/<id>/settings` route independently, on a slower cadence, is simpler and
/// keeps the two processes' failure modes independent -- a helperd-side sync hiccup can't stall
/// screenshot capture, and vice versa.
public enum DashboardVisualFilterSettings {
    private static let refreshInterval: TimeInterval = 60
    private static let lock = NSLock()
    private static var lastFetchAttemptAt = Date.distantPast
    private static var cachedEnabled = true
    private static var cachedIntervalSeconds = FocusLockConstants.screenshotScanInterval

    /// Returns the last-known settings immediately -- defaulting to enabled/30s before the first
    /// successful fetch, the same "fail toward more restrictive" stance the phone's `?: true`
    /// default takes -- and kicks off a background refresh if the cache is stale. Never blocks the
    /// caller on the network round trip.
    public static func current() -> (enabled: Bool, intervalSeconds: Double) {
        refreshIfStale()
        lock.lock()
        defer { lock.unlock() }
        return (cachedEnabled, cachedIntervalSeconds)
    }

    private static func refreshIfStale() {
        lock.lock()
        let dueForAttempt = Date().timeIntervalSince(lastFetchAttemptAt) >= refreshInterval
        if dueForAttempt { lastFetchAttemptAt = Date() }
        lock.unlock()
        guard dueForAttempt else { return }

        let host = TamperReporter.resolvedHost()
        let token = TamperReporter.resolvedToken()
        guard !host.isEmpty, !token.isEmpty,
              let deviceID = TamperReporter.deviceID(), !deviceID.isEmpty,
              let url = URL(string: "https://\(host)/dashboard-api/devices/\(deviceID)/settings") else {
            return
        }

        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        URLSession.shared.dataTask(with: request) { data, response, error in
            guard error == nil,
                  let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return
            }
            lock.lock()
            defer { lock.unlock() }
            if let enabled = json["visualFilterEnabled"] as? Bool {
                cachedEnabled = enabled
            }
            if let interval = json["visualFilterIntervalSeconds"] as? Int {
                cachedIntervalSeconds = max(FocusLockConstants.screenshotMinScanInterval, Double(interval))
            }
        }.resume()
    }
}
