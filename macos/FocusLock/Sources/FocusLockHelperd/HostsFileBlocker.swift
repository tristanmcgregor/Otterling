import Foundation
import FocusLockShared

/// Redirects blocked domains to localhost via /etc/hosts. This is the primary, always-effective
/// blocking mechanism: it works regardless of which browser or app is making the request, as
/// long as that app resolves hostnames through the system resolver.
enum HostsFileBlocker {
    private static let hostsPath = "/etc/hosts"

    static func apply(domains: [String]) {
        guard let original = try? String(contentsOfFile: hostsPath, encoding: .utf8) else { return }
        // A nil strip means the file's managed block is malformed (BEGIN with no END). Bail rather
        // than rewrite -- see stripManagedBlock's doc comment for the data loss this prevents.
        guard let stripped = HostsFileBlock.strip(from: original) else {
            FileHandle.standardError.write(
                "[hosts] /etc/hosts has a BEGIN marker with no matching END -- refusing to rewrite it. Repair the file by hand (remove the stray marker) and enforcement will resume on the next tick.\n".data(using: .utf8)!
            )
            return
        }

        // Hard safety cap, independent of any caller: an oversized /etc/hosts cripples mDNSResponder
        // and takes the whole machine offline. Callers are expected to prioritise the Guardian's
        // manual domains ahead of the bulk list (see EnforcementLoop), so truncating the tail here
        // drops only bulk-list entries, never the intentional ones. See maxHostsBlocklistDomains.
        let cap = FocusLockConstants.maxHostsBlocklistDomains
        let safeDomains: [String]
        if domains.count > cap {
            FileHandle.standardError.write(
                "[hosts] blocklist of \(domains.count) domains exceeds the \(cap) cap -- writing the first \(cap) only to protect DNS resolution\n".data(using: .utf8)!
            )
            safeDomains = Array(domains.prefix(cap))
        } else {
            safeDomains = domains
        }

        let newContent: String
        if safeDomains.isEmpty {
            newContent = stripped
        } else {
            var lines = [FocusLockConstants.hostsMarkerBegin]
            for domain in safeDomains {
                lines.append(contentsOf: HostsFileBlock.lines(for: domain))
            }
            lines.append(FocusLockConstants.hostsMarkerEnd)
            let trimmedBase = stripped.trimmingCharacters(in: .newlines)
            newContent = trimmedBase + "\n\n" + lines.joined(separator: "\n") + "\n"
        }

        guard newContent != original else { return }
        try? newContent.write(toFile: hostsPath, atomically: true, encoding: .utf8)
        flushDNSCache()
    }

    private static func flushDNSCache() {
        ProcessRunner.runSilently("/usr/bin/dscacheutil", ["-flushcache"])
        ProcessRunner.runSilently("/usr/bin/killall", ["-HUP", "mDNSResponder"])
    }
}
