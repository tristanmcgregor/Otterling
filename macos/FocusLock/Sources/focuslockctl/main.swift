import Darwin
import Foundation
import FocusLockShared

/// Command-line override tool. Functionally this is just a thin wrapper around the same XPC
/// calls the GUI makes -- the actual enforcement lives in the daemon (`XPCService.authorize`),
/// not here. Running this without the Guardian passcode gets exactly the same "denied" replies
/// as the GUI would.
func printUsage() {
    print("""
    focuslockctl -- Otterling command-line control

    Blocking is unconditional and permanent: anything added below is enforced 24/7 until removed.
    Adding is always allowed and immediate. Removing needs the Guardian passcode AND waits out a
    cooldown before it takes effect -- see `set-passcode` / `set-cooldown` below.

    Usage:
      focuslockctl status
      focuslockctl add-domain <domain>
      focuslockctl remove-domain <domain>                 (passcode + cooldown)
      focuslockctl add-app <displayName> <executableName>
      focuslockctl remove-app <executableName>            (passcode + cooldown)

      focuslockctl add-protected-app <displayName> <executableName> <bundlePath>
      focuslockctl remove-protected-app <executableName>  (passcode + cooldown)

      focuslockctl enable-dns
      focuslockctl disable-dns                            (passcode + cooldown)
      focuslockctl set-filter-host <host>                 (passcode + cooldown)

      focuslockctl enable-proxy [--force]                 (route web through mitmproxy; --force also
                                                           firewall-blocks direct :80/:443)
      focuslockctl disable-proxy                          (passcode + cooldown)

      focuslockctl set-passcode                           (prompts; no passcode set = no prompt)
      focuslockctl clear-passcode                         (passcode + cooldown)
      focuslockctl set-cooldown <hours>                   (raising is free; lowering is gated)
      focuslockctl cancel <pendingActionId>               (always allowed, no passcode)

      focuslockctl check-update
      focuslockctl install-update                         (passcode, no cooldown)

    The two gates, and why they're shaped this way:

      Passcode -- once set, it replaces the `admin`-group check entirely. That check assumed the
      Guardian-account split (your daily account Standard, a separate account admin); on a machine
      where you are the only admin it grants you everything, which is the hole this closes. Give
      the passcode to someone else, or store it somewhere you can't reach on impulse. Passcodes are
      read from a prompt, never argv -- `ps` can expose another process's arguments.

      Cooldown -- a correct passcode SCHEDULES the change rather than making it. It lands
      `cooldownHours` later (default 24h) and is reported to the filter server the moment it's
      requested. Anyone can cancel a scheduled change without the passcode, because cancelling
      restores protection.

    Neither gate stops a local admin with a terminal: you can always unload the daemon. The
    watchdog re-bootstraps it and the tamper report is filed either way, so what you cannot do is
    remove protection QUIETLY or IMPULSIVELY. That is the whole claim -- see GUARDIAN_SETUP.md.

    Protected apps (e.g. an accountability app) can't be quit -- the daemon relaunches them
    within seconds -- or deleted -- their bundle is locked with the filesystem-level immutable
    flag, which only root can clear.

    Content filter (NSFW): a downloaded local adult-domain hosts list is applied unconditionally,
    always. DNS enforcement additionally points every network service at a configurable cloud
    filter server (`set-filter-host`, default vpn.bartholomew.help) and blocks alternate/DoH/DoT
    resolvers so it can't be sidestepped by just picking a different one -- falling back to
    Cloudflare Family (1.1.1.3 / 1.0.0.3) if the cloud filter is off or its host can't be resolved.
    """)
}

/// Deadline for the reply-pumping loop at the bottom of this file. Declared up here because
/// `prompt` has to push it back: a human typing a passcode routinely takes longer than the XPC
/// timeout, and without this the tool would abort mid-prompt and report a daemon that never
/// answered.
var replyDeadline = Date().addingTimeInterval(20)

/// Reads a passcode without echoing it. Deliberately not a CLI argument: `ps` can show another
/// process's argv, and the whole point of the passcode is that the machine's user may not hold it
/// -- handing it to them in a process listing would defeat that.
func prompt(_ message: String) -> String {
    defer { replyDeadline = Date().addingTimeInterval(20) }
    guard let raw = getpass(message) else { return "" }
    return String(cString: raw)
}

/// Only prompts when the daemon actually has a passcode configured, so an install that hasn't
/// opted in keeps the old friction-free behaviour.
func passcodeIfConfigured(_ state: FocusLockState?) -> String {
    guard state?.passcodeConfigured == true else { return "" }
    return prompt("Guardian passcode: ")
}

func formatHours(_ hours: Double) -> String {
    if hours <= 0 { return "none (changes apply immediately)" }
    if hours < 1 { return "\(Int((hours * 60).rounded()))m" }
    return hours == hours.rounded() ? "\(Int(hours))h" : String(format: "%.1fh", hours)
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
    if state.proxyEnforcementEnabled {
        let force = state.forceProxyViaFirewall ? " + firewall force-through (:80/:443 locked to proxy)" : ""
        lines.append("Proxy enforcement: ON\(force) — \(state.proxyHost):\(state.proxyPort)")
    } else {
        lines.append("Proxy enforcement: off")
    }

    if state.passcodeConfigured {
        lines.append("Guardian passcode: set")
    } else {
        lines.append("⚠️  Guardian passcode: NOT set -- removals fall back to the admin-group check, " +
                      "which grants everything to any admin account (including this one). " +
                      "Run `focuslockctl set-passcode`.")
    }
    lines.append("Removal cooldown: \(formatHours(state.cooldownHours))")

    if !state.pendingActions.isEmpty {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        lines.append("Pending changes (\(state.pendingActions.count)) -- cancel with `focuslockctl cancel <id>`:")
        for action in state.pendingActions.sorted(by: { $0.effectiveAt < $1.effectiveAt }) {
            let when = action.isMature()
                ? "due now"
                : "takes effect \(formatter.string(from: action.effectiveAt))"
            lines.append("  - \(action.describedFully) -- \(when)")
            lines.append("      id: \(action.id)")
        }
    }
    if state.lockProfileInstalled {
        lines.append("Lock profile: installed")
    } else {
        lines.append("⚠️  Lock profile: NOT installed -- DNS floor + removal tripwire are missing. " +
                      "See GUARDIAN_SETUP.md / Scripts/install_lock_profile.command.")
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

// NSXPCConnection delivers its reply blocks on the main dispatch queue by default. A plain
// `DispatchSemaphore.wait()` on the main thread blocks that thread outright -- it does NOT drain
// the main queue while waiting -- so any XPC reply scheduled there would never run and this
// process would hang forever (this was previously observed: dozens of `focuslockctl` processes
// stuck in uninterruptible sleep). Polling `RunLoop.main.run(...)` in short slices instead keeps
// the main queue/run loop pumping between checks, so queued XPC callbacks actually get to fire.
var finished = false
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
        printResult(await client.removeBlockedDomain(arguments[2], passcode: passcodeIfConfigured(await client.getStatus())))

    case "add-app":
        guard arguments.count >= 4 else { printUsage(); finished = true; exit(1) }
        let app = BlockedApp(displayName: arguments[2], executableName: arguments[3])
        printResult(await client.addBlockedApp(app))

    case "remove-app":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.removeBlockedApp(executableName: arguments[2], passcode: passcodeIfConfigured(await client.getStatus())))

    case "add-protected-app":
        guard arguments.count >= 5 else { printUsage(); finished = true; exit(1) }
        let app = ProtectedApp(displayName: arguments[2], executableName: arguments[3], bundlePath: arguments[4])
        printResult(await client.addProtectedApp(app))

    case "remove-protected-app":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.removeProtectedApp(executableName: arguments[2], passcode: passcodeIfConfigured(await client.getStatus())))

    case "enable-dns":
        printResult(await client.enableDNSEnforcement())

    case "disable-dns":
        printResult(await client.disableDNSEnforcement(passcode: passcodeIfConfigured(await client.getStatus())))

    case "set-filter-host":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.setCloudFilterHost(arguments[2], passcode: passcodeIfConfigured(await client.getStatus())))

    case "enable-proxy":
        printResult(await client.enableProxyEnforcement(forceViaFirewall: arguments.contains("--force")))

    case "disable-proxy":
        printResult(await client.disableProxyEnforcement(passcode: passcodeIfConfigured(await client.getStatus())))

    case "set-passcode":
        let state = await client.getStatus()
        guard state != nil else {
            print("Could not reach FocusLockHelperd. Is it registered and running?")
            finished = true
            exit(1)
        }
        // Only asked for when one already exists -- setting the first passcode is ungated, since
        // it only ever adds a gate where there was none.
        let current = state?.passcodeConfigured == true ? prompt("Current Guardian passcode: ") : ""
        let new = prompt("New Guardian passcode (min 6 chars): ")
        guard !new.isEmpty else {
            print("DENIED: empty passcode. Use `clear-passcode` to remove it instead.")
            finished = true
            exit(1)
        }
        guard new == prompt("Confirm new Guardian passcode: ") else {
            print("DENIED: passcodes did not match. Nothing was changed.")
            finished = true
            exit(1)
        }
        printResult(await client.setGuardianPasscode(newPasscode: new, currentPasscode: current))

    case "clear-passcode":
        // Empty newPasscode is the daemon's signal to queue removal of the passcode entirely.
        printResult(await client.setGuardianPasscode(
            newPasscode: "",
            currentPasscode: passcodeIfConfigured(await client.getStatus())
        ))

    case "set-cooldown":
        guard arguments.count >= 3, let hours = Double(arguments[2]) else { printUsage(); finished = true; exit(1) }
        let state = await client.getStatus()
        // Raising the cooldown is ungated, so don't ask for a passcode we don't need.
        let needsPasscode = hours < (state?.cooldownHours ?? 0)
        printResult(await client.setCooldownHours(hours, passcode: needsPasscode ? passcodeIfConfigured(state) : ""))

    case "cancel":
        guard arguments.count >= 3 else { printUsage(); finished = true; exit(1) }
        printResult(await client.cancelPendingAction(id: arguments[2]))

    case "check-update":
        switch await client.checkForUpdate() {
        case .upToDate:
            print("Up to date (build \(FocusLockConstants.appVersionCode)).")
        case .updateAvailable(let manifest):
            print("Update available: \(manifest.versionName). Run `focuslockctl install-update` to install.")
        case .error(let message):
            print("Check failed: \(message)")
        case nil:
            print("Could not reach FocusLockHelperd. Is it registered and running?")
        }

    case "install-update":
        switch await client.installAvailableUpdate(passcode: passcodeIfConfigured(await client.getStatus())) {
        case .installedPendingRestart(let manifest):
            print("Installed \(manifest.versionName). Restarting the filter daemon now.")
        case .rejected(let reason):
            print("DENIED: \(reason)")
        }

    default:
        printUsage()
    }
    finished = true
}
while !finished {
    if Date() > replyDeadline {
        print("Timed out waiting for FocusLockHelperd to reply. Is the daemon registered and running?")
        exit(1)
    }
    RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.05))
}
