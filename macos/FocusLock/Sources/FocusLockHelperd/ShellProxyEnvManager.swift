import Darwin
import Foundation
import FocusLockShared

/// Most CLI tools (the `claude` CLI, npm, pip, plain `curl` without `-x`, etc.) don't read macOS's
/// system-wide Network proxy settings the way GUI apps/browsers do -- Node/Python/Go tooling
/// specifically looks for `HTTP_PROXY`/`HTTPS_PROXY` shell environment variables instead. When
/// `forceProxyViaFirewall` is on, `PFBlocker` drops any direct :80/:443 connection that doesn't go
/// through the proxy (see that type's doc comment) -- so a CLI tool with neither variable set gets
/// a flat, unexplained connection-refused. This keeps a managed block in the console user's shell
/// startup files in sync with the exact same target/credentials `ProxyEnforcer` already uses for
/// the system-wide GUI proxy setting, so opening a fresh terminal picks it up automatically.
///
/// Getting routed through the proxy is only half of it: mitmproxy then MITMs the TLS connection
/// and presents a leaf cert signed by its own CA. `setup_mac_proxy.command` trusts that CA in the
/// System keychain, which covers GUI apps/browsers, but Node/Python-based CLI tools -- Claude Code
/// among them -- validate against their own bundled root store instead (or, for Node, an
/// `NODE_EXTRA_CA_CERTS` env var) and know nothing about the System keychain. Without also pointing
/// those tools at `FocusLockConstants.proxyCACertPath`, every such tool fails TLS verification the
/// moment force-through is on, which looks exactly like "the network is blocked" even though the
/// system proxy setup is completely correct and browsers on the same Mac work fine.
///
/// Trade-off, confirmed accepted rather than silently assumed: this puts the proxy password in
/// plaintext inside the user's shell startup files, which get sourced into every terminal
/// session's environment -- a meaningfully wider exposure than the root-owned `proxy_password`
/// file `ProxyEnforcer` reads from, since any process the user runs afterward could read it back
/// out via its own environment. Accepted for the convenience of CLI tools (Claude Code among them)
/// working automatically under firewall force-through. The CA cert path carries no equivalent
/// secrecy trade-off -- it's a public certificate, not a credential (see filter-server/ca/README.md).
///
/// Same "reapply every tick, only writing when something actually changed" pattern as every other
/// enforcement in this daemon, not a one-time install step -- see `EnforcementLoop`'s call site.
enum ShellProxyEnvManager {
    private static let beginMarker = "# BEGIN OTTERLING PROXY (managed automatically -- do not edit by hand)"
    private static let endMarker = "# END OTTERLING PROXY"

    /// `active` should be exactly `EnforcementLoop`'s `forceProxyActive` (force-through on AND the
    /// proxy confirmed reachable this tick) -- these variables are only useful, and only set, when
    /// a direct connection would otherwise actually be blocked. `user`/`password` are the same
    /// values `ProxyEnforcer` already passes to `networksetup` for the system-wide proxy, so a
    /// shell-based tool gets identical access, not a separately-managed credential.
    static func apply(host: String, port: Int, user: String, password: String, active: Bool) {
        guard let uid = ConsoleUser.currentUID(), let (username, homeDir) = passwdEntry(uid: uid) else { return }
        for filename in [".zshrc", ".zprofile", ".bash_profile"] {
            reconcile(
                path: "\(homeDir)/\(filename)", host: host, port: port,
                user: user, password: password, active: active, owner: username
            )
        }
    }

    private static func reconcile(
        path: String, host: String, port: Int, user: String, password: String, active: Bool, owner: String
    ) {
        let existing = (try? String(contentsOfFile: path, encoding: .utf8)) ?? ""
        let withoutBlock = removeManagedBlock(from: existing)

        let desired: String
        if active {
            // URL-encode the password -- proxy_auth_gate.py's own generated passwords are
            // alphanumeric today, but this must not silently produce a broken proxy URL if that
            // ever changes to include a reserved character like ':' or '@'.
            let encodedPassword = password.addingPercentEncoding(withAllowedCharacters: .urlPasswordAllowed) ?? password
            let proxyURL = "http://\(user):\(encodedPassword)@\(host):\(port)"
            var lines = "export HTTPS_PROXY=\"\(proxyURL)\"\nexport HTTP_PROXY=\"\(proxyURL)\"\n"
            // Only advertise the CA if setup_mac_proxy.command actually provisioned it -- an env
            // var pointing at a missing file is worse than not setting it (some TLS stacks treat a
            // present-but-unreadable/unparseable CA bundle as "trust nothing" rather than falling
            // back to their default root store).
            if FileManager.default.fileExists(atPath: FocusLockConstants.proxyCACertPath) {
                let caPath = FocusLockConstants.proxyCACertPath
                lines += "export NODE_EXTRA_CA_CERTS=\"\(caPath)\"\n"
                lines += "export SSL_CERT_FILE=\"\(caPath)\"\n"
                lines += "export REQUESTS_CA_BUNDLE=\"\(caPath)\"\n"
            }
            let block = "\(beginMarker)\n\(lines)\(endMarker)\n"
            desired = withoutBlock.isEmpty || withoutBlock.hasSuffix("\n") ? withoutBlock + block : withoutBlock + "\n" + block
        } else {
            desired = withoutBlock
        }

        guard desired != existing else { return }
        guard let data = desired.data(using: .utf8) else { return }
        guard FileManager.default.createFile(atPath: path, contents: data) else { return }
        // Written by this root-owned daemon -- hand ownership back to the console user so their
        // own shell (and any editor) can read/edit the file normally afterward.
        try? FileManager.default.setAttributes([.ownerAccountName: owner], ofItemAtPath: path)
    }

    private static func removeManagedBlock(from content: String) -> String {
        guard let beginRange = content.range(of: beginMarker),
              let endMarkerRange = content.range(of: endMarker, range: beginRange.upperBound..<content.endIndex)
        else { return content }
        // Also swallow one trailing newline right after the end marker, if present, so repeated
        // enable/disable cycles don't accumulate blank lines.
        var upperBound = endMarkerRange.upperBound
        if upperBound < content.endIndex, content[upperBound] == "\n" {
            upperBound = content.index(after: upperBound)
        }
        var result = content
        result.removeSubrange(beginRange.lowerBound..<upperBound)
        return result
    }

    private static func passwdEntry(uid: uid_t) -> (username: String, homeDir: String)? {
        guard let pw = getpwuid(uid) else { return nil }
        return (String(cString: pw.pointee.pw_name), String(cString: pw.pointee.pw_dir))
    }
}
