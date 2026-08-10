import Foundation
import FocusLockShared

/// Redirects blocked domains to localhost via /etc/hosts. This is the primary, always-effective
/// blocking mechanism: it works regardless of which browser or app is making the request, as
/// long as that app resolves hostnames through the system resolver.
enum HostsFileBlocker {
    private static let hostsPath = "/etc/hosts"

    static func apply(domains: [String]) {
        guard let original = try? String(contentsOfFile: hostsPath, encoding: .utf8) else { return }
        let stripped = stripManagedBlock(from: original)

        let newContent: String
        if domains.isEmpty {
            newContent = stripped
        } else {
            var lines = [FocusLockConstants.hostsMarkerBegin]
            for domain in domains {
                lines.append("127.0.0.1 \(domain)")
                lines.append("127.0.0.1 www.\(domain)")
                lines.append("::1 \(domain)")
                lines.append("::1 www.\(domain)")
            }
            lines.append(FocusLockConstants.hostsMarkerEnd)
            let trimmedBase = stripped.trimmingCharacters(in: .newlines)
            newContent = trimmedBase + "\n\n" + lines.joined(separator: "\n") + "\n"
        }

        guard newContent != original else { return }
        try? newContent.write(toFile: hostsPath, atomically: true, encoding: .utf8)
        flushDNSCache()
    }

    private static func stripManagedBlock(from content: String) -> String {
        var result: [String] = []
        var inBlock = false
        for line in content.components(separatedBy: "\n") {
            // Recognizes both the current and the previous (FocusLock-branded) marker text, so an
            // upgrade cleans up a block written by an older build instead of leaving it orphaned.
            if line == FocusLockConstants.hostsMarkerBegin || line == FocusLockConstants.legacyHostsMarkerBegin {
                inBlock = true
                continue
            }
            if line == FocusLockConstants.hostsMarkerEnd || line == FocusLockConstants.legacyHostsMarkerEnd {
                inBlock = false
                continue
            }
            if inBlock { continue }
            result.append(line)
        }
        return result.joined(separator: "\n")
    }

    private static func flushDNSCache() {
        ProcessRunner.runSilently("/usr/bin/dscacheutil", ["-flushcache"])
        ProcessRunner.runSilently("/usr/bin/killall", ["-HUP", "mDNSResponder"])
    }
}
