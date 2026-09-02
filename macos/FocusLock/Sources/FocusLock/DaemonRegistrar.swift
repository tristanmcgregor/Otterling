import Foundation
import FocusLockShared
import ServiceManagement

/// One-time-per-launch registration of both LaunchDaemons (FocusLockHelperd and its watchdog,
/// FocusLockWatchdog -- see that target's doc comment) with SMAppService. If the user hasn't yet
/// approved them in System Settings > General > Login Items & Extensions, `register()` throws and
/// the service sits in `.requiresApproval` until they do -- there's nothing more the app can do to
/// force that beyond surfacing the prompt again on next launch.
enum DaemonRegistrar {
    /// Populated by a registration failure below, read once by `FocusLockViewModel` at startup and
    /// shown as a banner. Previously this only reached `NSLog` -- invisible unless someone thought
    /// to go looking in Console.app -- so a stuck registration (`.requiresApproval`, a corrupted
    /// Background Task Management database, or anything else `register()` can throw) could sit
    /// silently broken indefinitely with no on-screen indication anything was wrong at all.
    private(set) static var pendingWarnings: [String] = []

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
            let message = "FocusLockScanner (trigger-word/screenshot scanner) failed to register (status=\(service.status)): \(error.localizedDescription)"
            NSLog("FocusLock: \(message)")
            pendingWarnings.append(message)
        }
    }

    /// Per-user-agent equivalent of `launchdJobLoaded` below (which checks `system/<label>`) --
    /// the scanner lives in the `gui/<uid>` launchd domain, not `system`. Same "actually running,
    /// not just registered" requirement -- see that function's doc comment for why exit-status-only
    /// isn't enough.
    private static func scannerJobLoaded() -> Bool {
        let output = ProcessRunner.runCapturingStdout(
            "/bin/launchctl", ["print", "gui/\(getuid())/\(FocusLockConstants.scannerBundleIdentifier)"]
        )
        return output.contains("state = running")
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

        // `register()` right after `unregister()`'s completion handler fires can still throw
        // SMAppServiceErrorDomain Code=1 ("Operation not permitted") with `service.status` still
        // reporting stale `.enabled` -- confirmed live 2026-09-02: the completion handler fires
        // before the system has actually finished tearing down the old registration, so an
        // immediate re-register races it and loses. A few short, backed-off retries clear this
        // reliably without needing a fixed, worst-case-sized sleep on the common (no unregister
        // needed) path.
        var lastError: Error?
        for attempt in 0..<(wasStaleEnabled ? 5 : 1) {
            if attempt > 0 { Thread.sleep(forTimeInterval: 0.3 * Double(attempt)) }
            do {
                try service.register()
                if wasStaleEnabled {
                    TamperReporter.report(
                        type: "watchdog_or_daemon_reregistered",
                        details: "\(reportLabel) was enabled but unreachable on GUI launch -- re-registered"
                    )
                }
                return
            } catch {
                lastError = error
            }
        }
        let message = "\(reportLabel) failed to register (status=\(service.status)): \(lastError!.localizedDescription)"
        NSLog("FocusLock: \(message)")
        pendingWarnings.append(message)
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
    /// launchd currently have the job loaded AND actually running -- not just registered.
    ///
    /// A job whose `Program` points at a binary that's since disappeared (e.g. a build that lived
    /// under `/tmp` and got cleaned up by the system, or `/Applications/Otterling.app` having been
    /// reinstalled at a fresh path) still shows up in `launchctl print` and still exits 0: launchd
    /// keeps retrying it forever with exponential backoff, reporting `state = spawn scheduled` and
    /// `active count = 0`. Exit-status-only used to treat that as "loaded" -- SMAppService's own
    /// `.status == .enabled` check has exactly the same blind spot (see this function's caller) --
    /// so a daemon that can never actually spawn was never re-registered, silently, forever.
    /// Confirmed live: `app.otterling.watchdog` and `app.otterling.scanner` both stuck exactly like
    /// this, pointed at a deleted `/private/tmp/...` build, while `launchctl print` kept exiting 0.
    private static func launchdJobLoaded(label: String) -> Bool {
        let output = ProcessRunner.runCapturingStdout("/bin/launchctl", ["print", "system/\(label)"])
        return output.contains("state = running")
    }
}
