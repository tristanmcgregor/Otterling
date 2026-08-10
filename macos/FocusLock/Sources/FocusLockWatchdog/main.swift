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
        // First attempt: bootstrap, for the case where the job was fully unloaded (`bootout` or
        // never loaded this boot). If that fails because it's already loaded but just hung/wedged,
        // fall back to `kickstart -k`, which force-restarts an existing job.
        let bootstrapResult = ProcessRunner.run(
            "/bin/launchctl", ["bootstrap", "system", FocusLockConstants.helperLaunchDaemonPlistPath]
        )
        if bootstrapResult.status == 0 {
            TamperReporter.report(
                type: "daemon_unloaded_recovered",
                details: "FocusLockHelperd was unreachable; watchdog re-bootstrapped it"
            )
            return
        }

        let kickstartResult = ProcessRunner.run(
            "/bin/launchctl", ["kickstart", "-k", "system/\(FocusLockConstants.helperBundleIdentifier)"]
        )
        if kickstartResult.status == 0 {
            TamperReporter.report(
                type: "daemon_unloaded_recovered",
                details: "FocusLockHelperd was unreachable; watchdog force-restarted it via kickstart"
            )
        } else {
            FileHandle.standardError.write(
                ("[FocusLockWatchdog] recovery failed -- bootstrap: \(bootstrapResult.output.trimmingCharacters(in: .whitespacesAndNewlines)); " +
                 "kickstart: \(kickstartResult.output.trimmingCharacters(in: .whitespacesAndNewlines))\n").data(using: .utf8)!
            )
        }
    }
}

let watchdog = Watchdog()
watchdog.tick()
let watchdogTimer = Timer(timeInterval: 20, repeats: true) { _ in watchdog.tick() }
RunLoop.current.add(watchdogTimer, forMode: .common)

FileHandle.standardError.write("FocusLockWatchdog started\n".data(using: .utf8)!)
RunLoop.current.run()
