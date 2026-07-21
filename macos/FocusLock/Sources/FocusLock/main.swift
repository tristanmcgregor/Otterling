import Foundation
import ServiceManagement
import FocusLockShared

func log(_ message: String) {
    print(message)
    let line = message + "\n"
    if let data = line.data(using: .utf8) {
        let path = "/tmp/focuslock-debug.log"
        if !FileManager.default.fileExists(atPath: path) {
            FileManager.default.createFile(atPath: path, contents: nil)
        }
        if let handle = FileHandle(forWritingAtPath: path) {
            handle.seekToEndOfFile()
            handle.write(data)
            handle.closeFile()
        }
    }
}

let service = SMAppService.daemon(plistName: "au.com.tbmcgregor.bwparker.focuslock.helperd.plist")
log("Daemon status (before): \(service.status)")

// Dev-only: force a restart so code changes take effect without a manual sudo kill.
if service.status == .enabled, CommandLine.arguments.contains("--restart-daemon") {
    do {
        try service.unregister()
        log("Unregistered for restart.")
    } catch {
        log("Unregister failed: \(error)")
    }
    Thread.sleep(forTimeInterval: 1.0)
}

if service.status != .enabled {
    do {
        try service.register()
        log("Registered. Status: \(service.status)")
    } catch {
        log("Registration attempt result: \(error)")
    }
}
log("Daemon status (after): \(service.status)")

// --- Functional XPC smoke test (temporary, exercised via `Scripts/test_xpc.sh`) ---
let semaphore = DispatchSemaphore(value: 0)
Task {
    let client = FocusLockXPCClient()

    log("[test] getStatus (initial) -> \(String(describing: await client.getStatus()))")

    let app = BlockedApp(displayName: "Test App", executableName: "TestBlockedApp", bundleIdentifier: "com.example.test")
    log("[test] addBlockedApp -> \(await client.addBlockedApp(app))")
    log("[test] addBlockedDomain -> \(await client.addBlockedDomain("example.com"))")
    log("[test] startOrExtendSession(60s) -> \(await client.startOrExtendSession(durationSeconds: 60))")

    if let status = await client.getStatus() {
        log("[test] getStatus (after adds) -> apps=\(status.blockedApps) domains=\(status.blockedDomains) active=\(status.isSessionActive) remaining=\(Int(status.remainingSeconds))s")
    }

    log("[test] removeBlockedApp -> \(await client.removeBlockedApp(executableName: "TestBlockedApp"))")
    log("[test] endSessionEarly -> \(await client.endSessionEarly())")

    if let status = await client.getStatus() {
        log("[test] getStatus (after removes) -> apps=\(status.blockedApps) domains=\(status.blockedDomains) active=\(status.isSessionActive)")
    }

    semaphore.signal()
}
semaphore.wait()
