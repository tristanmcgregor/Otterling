import Foundation
import FocusLockShared

/// Downloads and caches adult-content hosts lists (same two sources as the Android app's
/// `DomainBlocklistManager`, for parity), merged into `HostsFileBlocker` by `EnforcementLoop`
/// alongside the Guardian's manual `blockedDomains` -- this is the local, always-on defense in
/// depth layer that keeps blocking known adult domains regardless of the cloud filter's state.
///
/// Refresh runs on its own background queue, never on the enforcement loop's queue: a stalled or
/// slow download must not delay app-kill/pf/hosts enforcement, which is why `refreshIfStale()` is
/// safe to call on every enforcement tick -- it's a cheap timestamp check that only dispatches
/// actual network work when the cache is missing or a day old.
///
/// The full list is loaded and applied uncapped (mirrors the Android app, which does the same);
/// on a modern Mac this is a few tens of thousands of /etc/hosts lines, which is fine for lookup
/// performance but is worth knowing about before treating hosts-file size as a red flag elsewhere.
final class AdultBlocklistManager {
    static let shared = AdultBlocklistManager()

    private let cacheFileURL = URL(fileURLWithPath: FocusLockConstants.adultBlocklistCachePath)
    private let queue = DispatchQueue(label: "app.otterling.adultblocklist")
    private let lock = NSLock()
    private var cached: [String]
    private var lastRefreshed: Date?

    private static let sources = [
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
        "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt",
    ]

    private init() {
        cached = Self.loadCache(from: cacheFileURL)
    }

    /// Cheap -- just returns whatever's cached in memory (loaded from disk at daemon start, kept
    /// current by `refreshIfStale`).
    func domains() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return cached
    }

    /// Kicks off a background download if the cache is missing or older than [interval]. Never
    /// blocks the caller.
    func refreshIfStale(interval: TimeInterval = 86_400) {
        lock.lock()
        let stale = lastRefreshed == nil || Date().timeIntervalSince(lastRefreshed!) >= interval
        lock.unlock()
        guard stale else { return }
        queue.async { [weak self] in self?.refreshNow() }
    }

    private func refreshNow() {
        var combined = Set<String>()
        for source in Self.sources {
            downloadHostsFile(source, into: &combined)
        }
        // An empty result means every source failed/changed format -- keep whatever's cached
        // rather than wiping out existing coverage (same "parsed to zero is a failure" rule as
        // the Android app's DomainBlocklistManager.refresh()).
        guard !combined.isEmpty else {
            lock.lock()
            lastRefreshed = Date()
            lock.unlock()
            return
        }
        let sorted = combined.sorted()
        try? sorted.joined(separator: "\n").write(to: cacheFileURL, atomically: true, encoding: .utf8)
        lock.lock()
        cached = sorted
        lastRefreshed = Date()
        lock.unlock()
    }

    private func downloadHostsFile(_ urlString: String, into domains: inout Set<String>) {
        guard let url = URL(string: urlString) else { return }
        // Synchronous fetch -- refreshNow() already runs off the daemon's serial enforcement
        // queue, on this manager's own dedicated background queue.
        let semaphore = DispatchSemaphore(value: 0)
        var body: Data?
        let task = URLSession.shared.dataTask(with: url) { data, _, _ in
            body = data
            semaphore.signal()
        }
        task.resume()
        _ = semaphore.wait(timeout: .now() + 20)
        guard let body, let text = String(data: body, encoding: .utf8) else { return }
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let line = rawLine.split(separator: "#", maxSplits: 1)[0].trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { continue }
            let parts = line.split(separator: " ").filter { !$0.isEmpty }
            guard parts.count >= 2, parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" else { continue }
            let domain = parts[1].lowercased()
            if domain != "localhost" { domains.insert(domain) }
        }
    }

    private static func loadCache(from url: URL) -> [String] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").map(String.init)
    }
}
