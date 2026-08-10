import Foundation

/// Validates that a caller-supplied domain is a plain hostname before it's ever interpolated into
/// a line written to root-owned `/etc/hosts` (see `XPCService.addBlockedDomain`) -- rejecting
/// anything but the character set hostnames are actually allowed to contain closes off newline
/// injection (an extra `/etc/hosts` line) and any other content-based abuse of that file write.
public enum HostnameValidator {
    public static func isValidHostname(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 253 else { return false }
        let labels = value.split(separator: ".", omittingEmptySubsequences: false)
        guard !labels.isEmpty else { return false }
        for label in labels {
            guard isValidLabel(label) else { return false }
        }
        return true
    }

    private static func isValidLabel(_ label: Substring) -> Bool {
        guard !label.isEmpty, label.count <= 63 else { return false }
        guard let first = label.first, let last = label.last else { return false }
        guard first != "-", last != "-" else { return false }
        return label.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
    }
}
