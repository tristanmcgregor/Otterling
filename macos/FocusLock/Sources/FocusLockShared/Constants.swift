import Foundation

/// Shared identifiers so the app, daemon, and CLI agree on bundle IDs, the XPC Mach service
/// name, and where root-owned state lives on disk.
public enum FocusLockConstants {
    public static let appBundleIdentifier = "au.com.tbmcgregor.bwparker.focuslock"
    public static let helperBundleIdentifier = "au.com.tbmcgregor.bwparker.focuslock.helperd"
    public static let machServiceName = helperBundleIdentifier

    public static let stateDirectory = "/Library/Application Support/FocusLock"
    public static let stateFilePath = "\(stateDirectory)/state.json"

    /// pf anchor the daemon loads its `block drop` rules into.
    public static let pfAnchorName = "au.com.tbmcgregor.focuslock"
    public static let pfAnchorFilePath = "/Library/Application Support/FocusLock/pf.anchor"

    /// Marker comment so the daemon can find/replace only the lines it owns in /etc/hosts.
    public static let hostsMarkerBegin = "# FocusLock BEGIN - do not edit, managed by FocusLockHelperd"
    public static let hostsMarkerEnd = "# FocusLock END"
}
