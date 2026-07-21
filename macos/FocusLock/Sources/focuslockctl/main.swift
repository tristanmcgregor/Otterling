import Foundation
import FocusLockShared

/// Command-line override tool. Functionally this is just a thin wrapper around the same XPC
/// calls the GUI makes -- the actual Guardian-account enforcement lives in the daemon
/// (`AdminGroupCheck` + `NSXPCConnection.current().effectiveUserIdentifier`), not here. Running
/// this from a Standard account gets exactly the same "denied" replies as the GUI would.
func printUsage() {
    print("""
    focuslockctl -- FocusLock command-line control

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

    Protected apps (e.g. an accountability app) can't be quit -- the daemon relaunches them
    within seconds -- or deleted -- their bundle is locked with the filesystem-level immutable
    flag, which only root can clear, so a Standard account can't touch it even with sudo.

    DNS enforcement points every network service at Cloudflare's content-filtering resolver
    (1.1.1.3 / 1.0.0.3) and blocks alternate/DoH resolvers so it can't be sidestepped by just
    picking a different one.
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
    lines.append("DNS enforcement: \(state.dnsEnforcementEnabled ? "ON (Cloudflare-filtered)" : "off")")
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

let semaphore = DispatchSemaphore(value: 0)
Task {
    switch command {
    case "status":
        if let state = await client.getStatus() {
            print(formatState(state))
        } else {
            print("Could not reach FocusLockHelperd. Is it registered and running?")
        }

    case "add-domain":
        guard arguments.count >= 3 else { printUsage(); semaphore.signal(); exit(1) }
        printResult(await client.addBlockedDomain(arguments[2]))

    case "remove-domain":
        guard arguments.count >= 3 else { printUsage(); semaphore.signal(); exit(1) }
        printResult(await client.removeBlockedDomain(arguments[2]))

    case "add-app":
        guard arguments.count >= 4 else { printUsage(); semaphore.signal(); exit(1) }
        let app = BlockedApp(displayName: arguments[2], executableName: arguments[3])
        printResult(await client.addBlockedApp(app))

    case "remove-app":
        guard arguments.count >= 3 else { printUsage(); semaphore.signal(); exit(1) }
        printResult(await client.removeBlockedApp(executableName: arguments[2]))

    case "add-protected-app":
        guard arguments.count >= 5 else { printUsage(); semaphore.signal(); exit(1) }
        let app = ProtectedApp(displayName: arguments[2], executableName: arguments[3], bundlePath: arguments[4])
        printResult(await client.addProtectedApp(app))

    case "remove-protected-app":
        guard arguments.count >= 3 else { printUsage(); semaphore.signal(); exit(1) }
        printResult(await client.removeProtectedApp(executableName: arguments[2]))

    case "enable-dns":
        printResult(await client.enableDNSEnforcement())

    case "disable-dns":
        printResult(await client.disableDNSEnforcement())

    default:
        printUsage()
    }
    semaphore.signal()
}
semaphore.wait()
