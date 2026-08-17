import Foundation

/// Reads `Contents/Resources/build-info.json`, written by `Scripts/build_app.sh` at build time (see
/// its own comment) -- the git commit and working-tree-dirty state captured at the moment this
/// binary was compiled. Read by `IntegrityReporter` to tell the server whether the running app was
/// built from a clean, committed source tree or from local, uncommitted changes.
public struct BuildProvenance: Codable {
    public let gitSha: String
    public let dirty: Bool
    public let builtAt: String

    enum CodingKeys: String, CodingKey {
        case gitSha = "git_sha"
        case dirty
        case builtAt = "built_at"
    }

    /// Locates `build-info.json` relative to the *running executable's* own path -- works the same
    /// way whether this is called from `FocusLockHelperd`, `FocusLockWatchdog`, or the main app, all
    /// of which live at `.../Contents/MacOS/<executable>` under the same bundle's `Contents/Resources`.
    public static func current() -> BuildProvenance? {
        let executableURL = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
        let resourcesURL = executableURL
            .deletingLastPathComponent() // MacOS/
            .deletingLastPathComponent() // Contents/
            .appendingPathComponent("Resources/build-info.json")
        guard let data = FileManager.default.contents(atPath: resourcesURL.path) else { return nil }
        return try? JSONDecoder().decode(BuildProvenance.self, from: data)
    }
}
