import Foundation
import FocusLockShared
import ServiceManagement

/// One-time-per-launch registration of both LaunchDaemons (FocusLockHelperd and its watchdog,
/// FocusLockWatchdog -- see that target's doc comment) with SMAppService. If the user hasn't yet
/// approved them in System Settings > General > Login Items & Extensions, `register()` throws and
/// the service sits in `.requiresApproval` until they do -- there's nothing more the app can do to
/// force that beyond surfacing the prompt again on next launch.
enum DaemonRegistrar {
    static func registerIfNeeded() {
        registerIfNeeded(
            plistName: "\(FocusLockConstants.helperBundleIdentifier).plist",
            isReachable: daemonIsReachable,
            reportLabel: "FocusLockHelperd"
        )
        registerIfNeeded(
            plistName: "\(FocusLockConstants.watchdogBundleIdentifier).plist",
            isReachable: { launchdJobLoaded(label: FocusLockConstants.watchdogBundleIdentifier) },
            reportLabel: "FocusLockWatchdog"
        )
        registerScannerAgentIfNeeded()
    }

    /// The trigger-word scanner is a per-user LaunchAgent, not a root daemon -- registered with
    /// `SMAppService.agent` (its plist lives in Contents/Library/LaunchAgents). Like the daemons it
    /// sits in `.requiresApproval` until the user allows it under Login Items & Extensions; on top
    /// of that it needs Accessibility permission, which the scanner itself prompts for on first run.
    ///
    /// Same stale-`.enabled`-but-unreachable re-register dance as `registerIfNeeded` below: this
    /// used to skip that check on the theory that the scanner was report-only, so a stale
    /// registration was only a missed alert -- but it now also runs the screenshot NSFW capture
    /// loop (`ScreenshotMonitor`), so a silently-dead scanner is a genuinely lifted protection, not
    /// just a missed alert. Confirmed via `launchctl print gui/<uid>/app.otterling.scanner`
    /// returning "Could not find service" while `SMAppService.status` still reported `.enabled`
    /// after a rebuild changed the binary underneath an existing registration.
    private static func registerScannerAgentIfNeeded() {
        let service = SMAppService.agent(plistName: "\(FocusLockConstants.scannerBundleIdentifier).plist")
        if service.status == .enabled, scannerJobLoaded() { return }

        let wasStaleEnabled = service.status == .enabled
        if wasStaleEnabled {
            let group = DispatchGroup()
            group.enter()
            service.unregister { _ in group.leave() }
            group.wait()
        }

        do {
            try service.register()
            if wasStaleEnabled {
                TamperReporter.report(
                    type: "watchdog_or_daemon_reregistered",
                    details: "FocusLockScanner was enabled but unreachable on GUI launch -- re-registered"
                )
            }
        } catch {
            NSLog("FocusLock: FocusLockScanner agent registration failed (status=\(service.status)): \(error)")
        }
    }

    /// Per-user-agent equivalent of `launchdJobLoaded` below (which checks `system/<label>`) --
    /// the scanner lives in the `gui/<uid>` launchd domain, not `system`.
    private static func scannerJobLoaded() -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = ["print", "gui/\(getuid())/\(FocusLockConstants.scannerBundleIdentifier)"]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return false }
        process.waitUntilExit()
        return process.terminationStatus == 0
    }

    /// SMAppService's cached `.status` can go stale relative to what launchd actually has loaded
    /// -- e.g. after the daemon plist/binary changes underneath an existing registration --
    /// reporting `.enabled` even though no job is running. `isReachable` confirms it's actually
    /// there before trusting that; if not, force a clean unregister+register instead of silently
    /// doing nothing forever, and -- unlike the pre-tamper-reporting version of this function --
    /// report it: `.enabled`-but-unreachable is exactly the shape of "something killed this
    /// outside its own XPC surface," distinct from a fresh install (which starts at
    /// `.notRegistered`, not `.enabled`, so this branch never fires for that case).
    private static func registerIfNeeded(plistName: String, isReachable: () -> Bool, reportLabel: String) {
        let service = SMAppService.daemon(plistName: plistName)
        if service.status == .enabled, isReachable() { return }

        let wasStaleEnabled = service.status == .enabled
        if wasStaleEnabled {
            let group = DispatchGroup()
            group.enter()
            service.unregister { _ in group.leave() }
            group.wait()
        }

        do {
            try service.register()
            if wasStaleEnabled {
                TamperReporter.report(
                    type: "watchdog_or_daemon_reregistered",
                    details: "\(reportLabel) was enabled but unreachable on GUI launch -- re-registered"
                )
            }
        } catch {
            NSLog("FocusLock: \(reportLabel) registration failed (status=\(service.status)): \(error)")
        }
    }

    private static func daemonIsReachable() -> Bool {
        let connection = NSXPCConnection(machServiceName: FocusLockConstants.machServiceName, options: .privileged)
        connection.remoteObjectInterface = NSXPCInterface(with: FocusLockXPCProtocol.self)
        connection.resume()
        defer { connection.invalidate() }

        let semaphore = DispatchSemaphore(value: 0)
        var reachable = false
        let proxy = connection.remoteObjectProxyWithErrorHandler { _ in semaphore.signal() } as? FocusLockXPCProtocol
        proxy?.getStatus { _ in
            reachable = true
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 2)
        return reachable
    }

    /// The watchdog has no XPC service of its own to ping, so this is the next best check: does
    /// launchd currently have the job loaded at all.
    private static func launchdJobLoaded(label: String) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = ["print", "system/\(label)"]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return false }
        process.waitUntilExit()
        return process.terminationStatus == 0
    }
}
