import Foundation
import FocusLockShared

/// Reverts DNS/proxy/pf to safe defaults before exiting, instead of leaving custom network
/// configuration behind for whatever signaled us to stop. Root-caused from a real incident: this
/// daemon had NO signal handling at all, so a SIGTERM from any source (a real shutdown, a stalled/
/// aborted one, `launchctl stop`, anything) just killed the process wherever it happened to be
/// mid-tick. Usually the watchdog relaunching it papers over that -- but if the system itself is in
/// a stuck/stalled shutdown at the time, nothing relaunches anything, and the custom DNS/pf state
/// this daemon had applied is left behind with no default networking to fall back to. Does NOT
/// depend on anything (a persisted flag, a relaunch) that could itself fail to happen -- it's the
/// signal handler itself doing the revert, synchronously, before the process actually exits.
func installGracefulShutdownHandler() {
    // Ignore the default disposition first -- otherwise the signal could terminate the process
    // before DispatchSource's handler ever gets to run.
    signal(SIGTERM, SIG_IGN)
    signal(SIGINT, SIG_IGN)

    func revertAndExit() {
        FileHandle.standardError.write("[shutdown] terminating -- reverting DNS/proxy/pf to safe defaults\n".data(using: .utf8)!)
        // Serialized onto EnforcementLoop's own queue for the same reason XPCService.killSwitch's
        // teardown is -- a tick already mid-flight (e.g. ProxyEnforcer.apply's own network probe)
        // could otherwise finish AFTER this and silently reapply what this just cleared. See
        // EnforcementLoop.runExclusive's doc comment.
        EnforcementLoop.shared.runExclusive {
            DNSEnforcer.remove()
            ProxyEnforcer.remove()
            PFBlocker.apply(active: false)
            ProcessRunner.runSilently("/sbin/pfctl", ["-d"])
        }
        exit(0)
    }

    let termSource = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
    termSource.setEventHandler(handler: revertAndExit)
    termSource.resume()

    let intSource = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
    intSource.setEventHandler(handler: revertAndExit)
    intSource.resume()

    // Held for the process lifetime -- a local `let` inside this function would be deallocated
    // (and the signal handling with it) as soon as the function returns.
    signalSources = [termSource, intSource]
}

private var signalSources: [DispatchSourceSignal] = []

installGracefulShutdownHandler()

let stateStore = StateStore()
let xpcService = XPCService(stateStore: stateStore) {
    EnforcementLoop.shared.reapplyNow()
}
let delegate = ListenerDelegate(exportedObject: xpcService)

let listener = NSXPCListener(machServiceName: FocusLockConstants.machServiceName)
listener.delegate = delegate
listener.resume()

EnforcementLoop.shared.start(stateStore: stateStore)
UpdateCheckLoop.shared.start(stateStore: stateStore)

FileHandle.standardError.write("FocusLockHelperd started, listening on \(FocusLockConstants.machServiceName)\n".data(using: .utf8)!)

RunLoop.current.run()
