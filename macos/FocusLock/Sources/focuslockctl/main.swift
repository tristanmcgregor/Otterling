import Foundation
import FocusLockShared

/// Command-line override tool. Functionally this is just a thin wrapper around the same XPC
/// calls the GUI makes -- the actual Guardian-account enforcement lives in the daemon
/// (`AdminGroupCheck` + `NSXPCConnection.current().effectiveUserIdentifier`), not here. Running
/// this from a Standard account gets exactly the same "denied" replies as the GUI would.
func printUsage() {
    print("""
    focuslockctl -- FocusLock command-line control

    Usage:
      focuslockctl status
      focuslockctl add-domain <domain>
      focuslockctl remove-domain <domain>          (Guardian admin account only)
      focuslockctl add-app <displayName> <executableName>
      focuslockctl remove-app <executableName>     (Guardian admin account only)
      focuslockctl start <hours>
      focuslockctl end-session                     (Guardian admin account only)
    """)
}

func formatState(_ state: FocusLockState) -> String {
    var lines: [String] = []
    lines.append("Session: \(state.isSessionActive ? "ACTIVE (\(Int(state.remainingSeconds))s remaining)" : "inactive")")
    lines.append("Blocked apps (\(state.blockedApps.count)):")
    for app in state.blockedApps {
        lines.append("  - \(app.displayName) [\(app.executableName)]")
    }
    lines.append("Blocked domains (\(state.blockedDomains.count)):")
    for domain in state.blockedDomains {
        lines.append("  - \(domain)")
    }
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

    case "start":
        guard arguments.count >= 3, let hours = Double(arguments[2]), hours > 0 else { printUsage(); semaphore.signal(); exit(1) }
        printResult(await client.startOrExtendSession(durationSeconds: hours * 3600))

    case "end-session":
        printResult(await client.endSessionEarly())

    default:
        printUsage()
    }
    semaphore.signal()
}
semaphore.wait()
