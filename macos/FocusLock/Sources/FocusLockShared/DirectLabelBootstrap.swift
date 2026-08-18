import Foundation

/// Shared "stuck SMAppService/BTM registration" recovery workaround -- see the memory notes on
/// this project's repeated encounters with it. `launchctl bootstrap` under a LaunchDaemon's real,
/// SMAppService-managed label can fail with "Bootstrap failed: 5: Input/output error" even when
/// nothing is currently loaded under that label, because Background Task Management's own
/// database is what's actually stuck, not launchd's live state. Bootstrapping the SAME plist under
/// a DIFFERENT label sidesteps that database entirely. Used by both `focuslockctl restore` and
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

    /// Bootstraps `label` from `plistPath` under the system domain, falling back to the
    /// `.direct`-label workaround if the real label is stuck. Returns a human-readable summary of
    /// what happened (bootstrapped normally / bootstrapped under `.direct` / failed entirely) for
    /// the caller to log or report however fits its own context.
    @discardableResult
    public static func bootstrapWithFallback(label: String, plistPath: String) -> String {
        ProcessRunner.runSilently("/bin/launchctl", ["bootout", "system/\(label)"])
        let result = ProcessRunner.run("/bin/launchctl", ["bootstrap", "system", plistPath])
        if result.status == 0 {
            return "\(label): bootstrapped"
        }

        guard let directPlistPath = writeDirectLabelPlist(sourcePlistPath: plistPath, originalLabel: label) else {
            return "\(label): real-label bootstrap failed (\(result.output.trimmingCharacters(in: .whitespacesAndNewlines))) and could not prepare the .direct-label plist"
        }
        ProcessRunner.runSilently("/bin/launchctl", ["bootout", "system/\(label).direct"])
        let directResult = ProcessRunner.run("/bin/launchctl", ["bootstrap", "system", directPlistPath])
        if directResult.status == 0 {
            return "\(label): real-label bootstrap failed, bootstrapped under \(label).direct instead"
        }
        return "\(label): both real-label and .direct-label bootstrap failed (\(directResult.output.trimmingCharacters(in: .whitespacesAndNewlines)))"
    }
}
