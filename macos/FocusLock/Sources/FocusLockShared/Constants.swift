import Foundation

/// Shared identifiers so the app, daemon, and CLI agree on bundle IDs, the XPC Mach service
/// name, and where root-owned state lives on disk.
public enum FocusLockConstants {
    public static let appBundleIdentifier = "au.com.tbmcgregor.bwparker.focuslock"
    public static let helperBundleIdentifier = "au.com.tbmcgregor.bwparker.focuslock.helperd"
    public static let machServiceName = helperBundleIdentifier

    public static let stateDirectory = "/Library/Application Support/FocusLock"
    public static let stateFilePath = "\(stateDirectory)/state.json"
    /// Cache file for the downloaded adult-domain hosts lists (see `AdultBlocklistManager`).
    public static let adultBlocklistCachePath = "\(stateDirectory)/adult_domains.txt"

    /// pf anchor the daemon loads its `block drop` rules into.
    public static let pfAnchorName = "au.com.tbmcgregor.focuslock"
    public static let pfAnchorFilePath = "/Library/Application Support/FocusLock/pf.anchor"

    /// Marker comment so the daemon can find/replace only the lines it owns in /etc/hosts.
    public static let hostsMarkerBegin = "# Otterling BEGIN - do not edit, managed by FocusLockHelperd"
    public static let hostsMarkerEnd = "# Otterling END"

    /// Marker comments so the daemon can find/replace only the anchor reference it owns in
    /// /etc/pf.conf, without touching Apple's or any other tool's rules.
    public static let pfConfMarkerBegin = "# Otterling BEGIN - do not edit, managed by FocusLockHelperd"
    public static let pfConfMarkerEnd = "# Otterling END"

    /// Previous brand's markers -- recognized alongside the current ones so an upgrade from an
    /// older (FocusLock-branded) build cleans up its own managed blocks instead of leaving them
    /// as permanently-orphaned entries in /etc/hosts / /etc/pf.conf that neither the old nor new
    /// marker text matches anymore.
    public static let legacyHostsMarkerBegin = "# FocusLock BEGIN - do not edit, managed by FocusLockHelperd"
    public static let legacyHostsMarkerEnd = "# FocusLock END"
    public static let legacyPFConfMarkerBegin = "# FocusLock BEGIN - do not edit, managed by FocusLockHelperd"

    /// Default cloud content-filter DNS host (a Canopy-style AdGuard Home deployment -- see
    /// filter-server/README.md at the repo root), mirroring the Android app's `CloudFilterSettings`.
    public static let defaultCloudFilterHost = "bartholomew.help"
}
