import Foundation
import FocusLockShared

/// Command-line override tool. Functionally this is just a thin wrapper around the same XPC
/// calls the GUI makes -- the actual Guardian-account enforcement lives in the daemon
/// (`AdminGroupCheck` + `NSXPCConnection.current().effectiveUserIdentifier`), not here. Running
/// this from a Standard account gets exactly the same "denied" replies as the GUI would.
func printUsage() {
    print("""
    focuslockctl -- Otterling command-line control

    Blocking is unconditional and permanent: anything added below is enforced 24/7 until
    removed. Adding is always allowed; removing requires the Guardian admin account.

    Usage:
      focuslockctl status
      focuslockctl add-domain <domain>
      focuslockctl remove-domain <domain>          (Guardian admin account only)
      focuslockctl add-app <displayName> <executableName>
      focuslockctl remove-app <executableName>     (Guardian admin account only)

      focuslockctl add-protected-app <displayName> <executableName> <bundlePath>
      focuslockctl remove-protected-app <executableName>  (Guardian admin account only)

      focuslockctl enable-dns
      focuslockctl disable-dns                     (Guardian admin account only)
      focuslockctl set-filter-host <host>

    Protected apps (e.g. an accountability app) can't be quit -- the daemon relaunches them
    within seconds -- or deleted -- their bundle is locked with the filesystem-level immutable
    flag, which only root can clear, so a Standard account can't touch it even with sudo. This is
    optional and not required for content filtering.

    Content filter (NSFW): a downloaded local adult-domain hosts list is applied unconditionally,
    always. DNS enforcement additionally points every network service at a configurable cloud
    filter server (`set-filter-host`, default vpn.bartholomew.help) and blocks alternate/DoH/DoT
    resolvers so it can't be sidestepped by just picking a different one -- falling back to
    Cloudflare Family (1.1.1.3 / 1.0.0.3) if the cloud filter is off or its host can't be resolved.

    The Guardian account password is set manually in System Settings (see GUARDIAN_SETUP.md);
    the phone PIN is set manually on the device.
    """)
}

func formatState(_ state: FocusLockState) -> String {
    var lines: [String] = []
    lines.append("Blocked apps (\(state.blockedApps.count)):")
    for app in state.blockedApps {
        lines.append("  - \(app.displayName) [\(app.executableName)]")
    }
    lines.append("Blocked domains (\(state.blockedDomains.count)):")
    for domain in state.blockedDomains {
        lines.append("  - \(domain)")
    }
    lines.append("Protected apps (\(state.protectedApps.count)):")
    for app in state.protectedApps {
        lines.append("  - \(app.displayName) [\(app.executableName)] @ \(app.bundlePath)")
    }
    lines.append("DNS enforcement: \(state.dnsEnforcementEnabled ? "ON" : "off")")
    lines.append("Cloud filter host: \(state.cloudFilterHost) (\(state.cloudFilterEnabled ? "enabled" : "disabled, Cloudflare Family fallback only"))")
    return lines.joined(separator: "\n")
}

func printResult(_ result: FocusLockResult) {
    if result.success {
        print("OK")
    } else {
        print("DENIED: \(result.message ?? "unknown reason")")
    }
}

let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
    printUsage()
    exit(1)
}

let client = FocusLockXPCClient()
let command = arguments[1]

// NSXPCConnection delivers its reply blocks on the main dispatch queue by default. A plain
// `DispatchSemaphore.wait()` on the main thread blocks that thread outright -- it does NOT drain
// the main queue while waiting -- so any XPC reply scheduled there would never run and this
// process would hang forever (this was previously observed: dozens of `focuslockctl` processes
// stuck in uninterruptible sleep). Polling `RunLoop.main.run(...)` in short slices instead keeps
// the main queue/run loop pumping between checks, so queued XPC callbacks actually get to fire.
var finished = false
let hardTimeout = Date().addingTimeInterval(20)
Task {
    switch command {
    case "status":
        if let state = await client.getStatus() {
            print(formatState(state))
        } else {
            print("Could not reach FocusLockHelperd. Is it registered and running?")
        }

    case "add-domain":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.addBlockedDomain(arguments[2]))

    case "remove-domain":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.removeBlockedDomain(arguments[2]))

    case "add-app":
        guard arguments.count >= 4 else { printUsage(); finished = true; exit(1) }
        let app = BlockedApp(displayName: arguments[2], executableName: arguments[3])
        printResult(await client.addBlockedApp(app))

    case "remove-app":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.removeBlockedApp(executableName: arguments[2]))

    case "add-protected-app":
        guard arguments.count >= 5 else { printUsage(); finished = true; exit(1) }
        let app = ProtectedApp(displayName: arguments[2], executableName: arguments[3], bundlePath: arguments[4])
        printResult(await client.addProtectedApp(app))

    case "remove-protected-app":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.removeProtectedApp(executableName: arguments[2]))

    case "enable-dns":
        printResult(await client.enableDNSEnforcement())

    case "disable-dns":
        printResult(await client.disableDNSEnforcement())

    case "set-filter-host":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.setCloudFilterHost(arguments[2]))

    default:
        printUsage()
    }
    finished = true
}
while !finished {
    if Date() > hardTimeout {
        print("Timed out waiting for FocusLockHelperd to reply. Is the daemon registered and running?")
        exit(1)
    }
    RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.05))
}
