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

if CommandLine.arguments.contains("--site-block-hold") {
    Task {
        let client = FocusLockXPCClient()
        log("[hold] addBlockedDomain -> \(await client.addBlockedDomain("example.com"))")
        log("[hold] startOrExtendSession(20s) -> \(await client.startOrExtendSession(durationSeconds: 20))")
        log("[hold] holding for inspection, will self-expire in 20s")
        semaphore.signal()
    }
    semaphore.wait()
    exit(0)
}

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

    // Kill-loop smoke test: block "sleep" by executable name, launch a real `sleep 120`
    // process, and confirm the daemon's enforcement loop kills it within a few seconds.
    let sleepApp = BlockedApp(displayName: "sleep", executableName: "sleep", bundleIdentifier: nil)
    log("[test] addBlockedApp(sleep) -> \(await client.addBlockedApp(sleepApp))")

    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: "/bin/sleep")
    proc.arguments = ["120"]
    try? proc.run()
    log("[test] launched /bin/sleep 120, pid=\(proc.processIdentifier)")

    for i in 0..<10 {
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        let stillRunning = proc.isRunning
        log("[test] after \(i + 1)s, sleep process running=\(stillRunning)")
        if !stillRunning { break }
    }

    log("[test] removeBlockedApp(sleep) -> \(await client.removeBlockedApp(executableName: "sleep"))")
    log("[test] removeBlockedApp -> \(await client.removeBlockedApp(executableName: "TestBlockedApp"))")
    log("[test] endSessionEarly -> \(await client.endSessionEarly())")

    if let status = await client.getStatus() {
        log("[test] getStatus (after removes) -> apps=\(status.blockedApps) domains=\(status.blockedDomains) active=\(status.isSessionActive)")
    }

    semaphore.signal()
}
semaphore.wait()
