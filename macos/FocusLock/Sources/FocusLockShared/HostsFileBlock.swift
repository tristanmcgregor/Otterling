import Foundation

/// The pure text half of `/etc/hosts` management: finding and removing the block this app owns.
///
/// Split out of `HostsFileBlocker` (which lives in the daemon's executable target, where no test
/// target can import it) specifically so the failure mode below is covered by tests. Rewriting
/// `/etc/hosts` as root is one of the few things this app does that can take a machine off the
/// network entirely -- the project has already had one such incident from the opposite direction,
/// a 4-million-line hosts file that crippled mDNSResponder -- so the parsing deserves assertions
/// rather than only a careful read.
public enum HostsFileBlock {
    /// Returns `content` with our managed block removed, or nil if the block is malformed
    /// (a BEGIN marker with no matching END).
    ///
    /// The nil case is the whole reason this function is tested. The original implementation set an
    /// `inBlock` flag at BEGIN and cleared it only on an exact END match, with no end-of-input
    /// guard -- so a missing END line (interrupted write, hand edit, or the legacy and current
    /// marker text mixed by an upgrade) meant every remaining line was treated as ours and
    /// discarded, `127.0.0.1 localhost` included. The next write then persisted that truncation.
    /// Callers must treat nil as "leave the file alone", never as "nothing to strip".
    public static func strip(from content: String) -> String? {
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

        guard !inBlock else { return nil }
        return result.joined(separator: "\n")
    }

    /// The lines this app writes for one blocked domain. Kept here beside `strip` so the write and
    /// the removal cannot drift apart.
    public static func lines(for domain: String) -> [String] {
        [
            "127.0.0.1 \(domain)",
            "127.0.0.1 www.\(domain)",
            "::1 \(domain)",
            "::1 www.\(domain)",
        ]
    }
}
