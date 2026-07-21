import Foundation
import ServiceManagement

/// One-time-per-launch registration of the LaunchDaemon with SMAppService. If the user hasn't
/// yet approved it in System Settings > General > Login Items & Extensions, `register()` throws
/// and the service sits in `.requiresApproval` until they do -- there's nothing more the app can
/// do to force that beyond surfacing the prompt again on next launch.
enum DaemonRegistrar {
    static func registerIfNeeded() {
        let service = SMAppService.daemon(plistName: "au.com.tbmcgregor.bwparker.focuslock.helperd.plist")
        guard service.status != .enabled else { return }
        do {
            try service.register()
        } catch {
            NSLog("FocusLock: daemon registration failed (status=\(service.status)): \(error)")
        }
    }
}
