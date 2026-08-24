import Foundation

struct InstalledApp {
    let executableName: String
    let displayName: String
}

/// Enumerates installed (not just running) applications for `DashboardConfigSync.reportInstalledApps`
/// -- the macOS equivalent of Android's `loadInstalledApps` (`InstalledAppPicker.kt`). Unlike
/// `ProcessScanner`, which only sees what's currently running, this reads `/Applications` and
/// `/System/Applications` directly so an app that isn't open right now still shows up when a
/// guardian searches for it on the dashboard.
enum InstalledAppScanner {
    private static let searchDirectories = ["/Applications", "/System/Applications"]

    /// `executableName` is each bundle's `CFBundleExecutable` -- the same string
    /// `AppBlockEnforcer`/`RuleBlockEnforcer` match running processes against (see
    /// `ProcessScanner.executableName`), not the bundle identifier or display name. A bundle
    /// missing that key (malformed or unusual bundle) is skipped rather than guessed at, since a
    /// wrong executable name would silently fail to match anything when used in a rule.
    static func listInstalledApps() -> [InstalledApp] {
        var seenExecutables = Set<String>()
        var results: [InstalledApp] = []
        for directory in searchDirectories {
            guard let entries = try? FileManager.default.contentsOfDirectory(atPath: directory) else { continue }
            for entry in entries where entry.hasSuffix(".app") {
                let bundlePath = (directory as NSString).appendingPathComponent(entry)
                guard let bundle = Bundle(path: bundlePath),
                      let executableName = bundle.object(forInfoDictionaryKey: "CFBundleExecutable") as? String,
                      !executableName.isEmpty,
                      !seenExecutables.contains(executableName) else { continue }
                seenExecutables.insert(executableName)
                let displayName = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
                    ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
                    ?? String(entry.dropLast(".app".count))
                results.append(InstalledApp(executableName: executableName, displayName: displayName))
            }
        }
        return results.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }
}
