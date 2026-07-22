import Foundation
import FocusLockShared
import ServiceManagement

/// One-time-per-launch registration of the LaunchDaemon with SMAppService. If the user hasn't
/// yet approved it in System Settings > General > Login Items & Extensions, `register()` throws
/// and the service sits in `.requiresApproval` until they do -- there's nothing more the app can
/// do to force that beyond surfacing the prompt again on next launch.
enum DaemonRegistrar {
    static func registerIfNeeded() {
        let service = SMAppService.daemon(plistName: "au.com.tbmcgregor.bwparker.focuslock.helperd.plist")

        // SMAppService's cached `.status` can go stale relative to what launchd actually has
        // loaded -- e.g. after the daemon plist/binary changes underneath an existing
        // registration -- reporting `.enabled` even though no job is running. Confirm the daemon
        // is actually reachable before trusting that; if not, force a clean unregister+register
        // instead of silently doing nothing forever.
        if service.status == .enabled, daemonIsReachable() { return }

        if service.status == .enabled {
            let group = DispatchGroup()
            group.enter()
            service.unregister { _ in group.leave() }
            group.wait()
        }

        do {
            try service.register()
        } catch {
            NSLog("FocusLock: daemon registration failed (status=\(service.status)): \(error)")
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
}
