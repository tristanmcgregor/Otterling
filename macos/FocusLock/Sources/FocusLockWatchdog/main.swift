import Foundation
import FocusLockShared

/// Independent LaunchDaemon (see `Constants.watchdogLaunchDaemonPlistPath`, embedded and
/// registered alongside FocusLockHelperd by `DaemonRegistrar`) whose only job is noticing that
/// FocusLockHelperd stopped responding and re-bootstrapping it. This is a *detection and reporting*
/// mechanism, not a prevention one -- see `GUARDIAN_SETUP.md` §5: anyone who can unload
/// FocusLockHelperd with `sudo launchctl bootout` can just as easily unload this watchdog too,
/// this only means they now have to do both instead of one, and each disappearance is itself
/// something the GUI's `DaemonRegistrar.registerIfNeeded()` reports the next time it runs.
///
/// Deliberately has no enforcement logic of its own and doesn't import anything from
/// FocusLockHelperd -- it only needs to know "is the daemon reachable" (via the same XPC status
/// call the GUI/CLI already make, `FocusLockXPCClient`) and "how do I reload it"
/// (`launchctl bootstrap`/`kickstart`).
final class Watchdog {
    private let client = FocusLockXPCClient()

    func tick() {
        Task {
            if await client.getStatus() != nil {
                return
            }
            FileHandle.standardError.write(
                "[FocusLockWatchdog] FocusLockHelperd unreachable -- attempting to reload it\n".data(using: .utf8)!
            )
            recover()
        }
    }

    private func recover() {
        // First attempt: kickstart under whichever label is CURRENTLY registered -- covers the
        // common case where a previous recovery (this watchdog's own, or `focuslockctl restore`)
        // already registered `.direct` and the daemon is just hung/wedged, not unloaded. Tries the
        // real label first, then `.direct`; either succeeding is a normal force-restart, not a
        // fresh bootstrap, so it's reported the same way either way.
        for label in [FocusLockConstants.helperBundleIdentifier, "\(FocusLockConstants.helperBundleIdentifier).direct"] {
            let kickstartResult = ProcessRunner.run("/bin/launchctl", ["kickstart", "-k", "system/\(label)"])
            if kickstartResult.status == 0 {
                TamperReporter.report(
                    type: "daemon_unloaded_recovered",
                    details: "FocusLockHelperd was unreachable; watchdog force-restarted it via kickstart (\(label))"
                )
                return
            }
        }

        // Nothing registered under either label at all (fully unloaded, e.g. after `bootout`) --
        // bootstrap fresh, automatically falling back to the `.direct`-label workaround if the
        // real label's own SMAppService/BTM registration is stuck (see DirectLabelBootstrap's doc
        // comment; this project has hit that repeatedly, and previously left the watchdog with no
        // way to recover from it at all).
        let summary = DirectLabelBootstrap.bootstrapWithFallback(
            label: FocusLockConstants.helperBundleIdentifier,
            plistPath: FocusLockConstants.helperLaunchDaemonPlistPath
        )
        if summary.contains("bootstrapped") {
            TamperReporter.report(type: "daemon_unloaded_recovered", details: "FocusLockHelperd was unreachable; watchdog re-bootstrapped it (\(summary))")
        } else {
            FileHandle.standardError.write("[FocusLockWatchdog] recovery failed -- \(summary)\n".data(using: .utf8)!)
        }
    }
}

let watchdog = Watchdog()
watchdog.tick()
let watchdogTimer = Timer(timeInterval: 20, repeats: true) { _ in watchdog.tick() }
RunLoop.current.add(watchdogTimer, forMode: .common)

FileHandle.standardError.write("FocusLockWatchdog started\n".data(using: .utf8)!)
RunLoop.current.run()
