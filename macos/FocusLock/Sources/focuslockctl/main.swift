import Foundation
import FocusLockShared
import Security

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

      focuslockctl guardian-pubkey
      focuslockctl guardian-link <relay-server-base-url> <phone-pubkey-base64>
      focuslockctl guardian-claim <relay-server-base-url> <token>

    Protected apps (e.g. an accountability app) can't be quit -- the daemon relaunches them
    within seconds -- or deleted -- their bundle is locked with the filesystem-level immutable
    flag, which only root can clear, so a Standard account can't touch it even with sudo.

    DNS enforcement points every network service at Cloudflare's content-filtering resolver
    (1.1.1.3 / 1.0.0.3) and blocks alternate/DoH resolvers so it can't be sidestepped by just
    picking a different one.

    guardian-link builds a one-time setup URL to send to your Guardian: they open it, choose a
    Mac account password and a phone PIN, and their browser encrypts each separately against this
    Mac's and the phone's public key before it ever reaches the relay server -- so whoever runs
    that server (even you) only ever sees ciphertext. guardian-claim then fetches and decrypts
    the Mac's half and applies it directly; you never see the plaintext either. The phone claims
    its own half independently, from the app.
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

    case "guardian-pubkey":
        if let key = await client.getGuardianSetupPublicKey() {
            print(key)
        } else {
            print("Could not read/create this Mac's Guardian setup keypair.")
        }

    case "guardian-link":
        guard arguments.count >= 4 else { printUsage(); finished = true; exit(1) }
        let serverBase = arguments[2].hasSuffix("/") ? String(arguments[2].dropLast()) : arguments[2]
        let phonePubKey = arguments[3]
        guard let macPubKey = await client.getGuardianSetupPublicKey() else {
            print("Could not read/create this Mac's Guardian setup keypair.")
            finished = true
            exit(1)
        }
        let token = randomURLSafeToken()
        // NOT using URLComponents.queryItems here: it leaves `+` and `/` unescaped in query
        // values (they're technically legal raw query characters per RFC 3986), but Flask/
        // Werkzeug -- like most form-decoders -- treats a literal `+` in a query string as an
        // encoded space. Standard base64 (used for both keys) is full of `+`/`/`, so that silently
        // corrupted the public keys before they ever reached the browser, breaking `atob()` there.
        // Percent-encoding every non-alphanumeric byte sidesteps that ambiguity entirely.
        let url = "\(serverBase)/setup/\(token)?mac_pub=\(percentEncodeQueryValue(macPubKey))&phone_pub=\(percentEncodeQueryValue(phonePubKey))"
        print("Send this link to your Guardian (expires in 30 minutes, single use):")
        print(url)
        print("")
        print("Once they've submitted it, run:")
        print("  focuslockctl guardian-claim \(serverBase) \(token)")

    case "guardian-claim":
        guard arguments.count >= 4 else { printUsage(); finished = true; exit(1) }
        let serverBase = arguments[2].hasSuffix("/") ? String(arguments[2].dropLast()) : arguments[2]
        let token = arguments[3]
        guard let url = URL(string: "\(serverBase)/drop/\(token)/mac") else {
            print("Invalid server URL.")
            finished = true
            exit(1)
        }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                print("Nothing to claim yet -- has the Guardian submitted the link?")
                finished = true
                exit(1)
            }
            guard
                let json = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                let ciphertext = json["ciphertext"]
            else {
                print("Malformed response from relay server.")
                finished = true
                exit(1)
            }
            printResult(await client.applyGuardianSetupCiphertext(ciphertext))
        } catch {
            print("Failed to reach relay server: \(error.localizedDescription)")
        }

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

/// Percent-encodes every byte outside RFC 3986's unreserved set (letters, digits, `-._~`). Unlike
/// `URLComponents`/`addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`, this also
/// escapes `+` and `/` -- both legal in a raw query string but ambiguous once a form-decoder on
/// the other end (e.g. Flask) is involved, since `+` conventionally means "space" there.
func percentEncodeQueryValue(_ value: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}

func randomURLSafeToken() -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    return Data(bytes).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}
