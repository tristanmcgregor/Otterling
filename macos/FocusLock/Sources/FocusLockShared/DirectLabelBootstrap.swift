import Foundation

/// Shared "stuck SMAppService/BTM registration" recovery workaround -- see the memory notes on
/// this project's repeated encounters with it. `launchctl bootstrap` under a LaunchDaemon's real,
/// SMAppService-managed label can fail with "Bootstrap failed: 5: Input/output error" even when
/// nothing is currently loaded under that label, because Background Task Management's own
/// database is what's actually stuck, not launchd's live state. Bootstrapping the SAME plist under
/// a DIFFERENT label sidesteps that database entirely. Used by both `otterlingctl restore` and
/// `FocusLockWatchdog`'s own recovery loop -- previously duplicated logic in the former only, which
/// left the watchdog unable to recover from exactly this failure mode.
public enum DirectLabelBootstrap {
    /// Writes a copy of `sourcePlistPath` under `/Library/LaunchDaemons` with its `Label` (and
    /// nothing else) changed to `<originalLabel>.direct`. `MachServices` is left pointing at the
    /// ORIGINAL mach service name, so XPC clients still connect to it under the name they already
    /// expect. Returns the path written, or nil if the source plist couldn't be read/parsed.
    public static func writeDirectLabelPlist(sourcePlistPath: String, originalLabel: String) -> String? {
        guard let data = FileManager.default.contents(atPath: sourcePlistPath),
              var plist = (try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)) as? [String: Any] else {
            return nil
        }
        plist["Label"] = "\(originalLabel).direct"
        guard let newData = try? PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0) else {
            return nil
        }
        let targetPath = "/Library/LaunchDaemons/\(originalLabel).direct.plist"
        guard (try? newData.write(to: URL(fileURLWithPath: targetPath))) != nil else { return nil }
        return targetPath
    }

    /// Removes a previously-written `.direct`-label plist, if any, so it can't survive to the next
    /// boot and reload itself via `RunAtLoad` after the real label has recovered.
    private static func removeDirectLabelPlist(originalLabel: String) {
        try? FileManager.default.removeItem(atPath: "/Library/LaunchDaemons/\(originalLabel).direct.plist")
    }

    /// Bootstraps `label` from `plistPath` under the system domain, falling back to the
    /// `.direct`-label workaround if the real label is stuck. Returns a human-readable summary of
    /// what happened (bootstrapped normally / bootstrapped under `.direct` / failed entirely) for
    /// the caller to log or report however fits its own context.
    @discardableResult
    public static func bootstrapWithFallback(label: String, plistPath: String) -> String {
        // Boot out BOTH labels before trying either bootstrap, not just the one we're about to
        // (re)try. Confirmed live: a stale `.direct` job left running from a previous fallback
        // (e.g. this watchdog's own earlier recovery, or a prior `otterlingctl restore`) still
        // holds the shared `MachServices` name when the real label's BTM registration later
        // clears up on its own -- bootstrapping the real label on top of that doesn't replace it,
        // it collides with it, so which job actually owns the mach port (and therefore answers
        // XPC calls) becomes a coin flip per connection instead of a hard failure. Clearing both
        // first guarantees at most one job ever holds the mach service at a time.
        ProcessRunner.runSilently("/bin/launchctl", ["bootout", "system/\(label)"])
        ProcessRunner.runSilently("/bin/launchctl", ["bootout", "system/\(label).direct"])
        let result = ProcessRunner.run("/bin/launchctl", ["bootstrap", "system", plistPath])
        if result.status == 0 {
            // Booting the `.direct` job out above only unloads it for this session -- its plist,
            // if one was ever written by a previous fallback, is still sitting in
            // `/Library/LaunchDaemons` with `RunAtLoad = true`. Confirmed live 2026-09-02: left in
            // place, launchd happily reloads it on the very next boot (before this code ever runs
            // again to boot it back out), producing a second live process racing the real label for
            // the same Mach service every time the machine restarts. Deleting it here, the one place
            // that knows the real label just came back up cleanly, is what actually makes the
            // `.direct` fallback temporary instead of a permanent, silently-reappearing duplicate.
            removeDirectLabelPlist(originalLabel: label)
            return "\(label): bootstrapped"
        }

        guard let directPlistPath = writeDirectLabelPlist(sourcePlistPath: plistPath, originalLabel: label) else {
            return "\(label): real-label bootstrap failed (\(result.output.trimmingCharacters(in: .whitespacesAndNewlines))) and could not prepare the .direct-label plist"
        }
        let directResult = ProcessRunner.run("/bin/launchctl", ["bootstrap", "system", directPlistPath])
        if directResult.status == 0 {
            return "\(label): real-label bootstrap failed, bootstrapped under \(label).direct instead"
        }
        return "\(label): both real-label and .direct-label bootstrap failed (\(directResult.output.trimmingCharacters(in: .whitespacesAndNewlines)))"
    }
}
