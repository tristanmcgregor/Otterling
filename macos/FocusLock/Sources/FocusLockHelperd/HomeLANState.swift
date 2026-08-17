import Foundation
import FocusLockShared

/// Debounced "am I currently on the home LAN" signal, shared by `DNSEnforcer` and `ProxyEnforcer`
/// so they always agree and only pay for the TLS verification once per tick rather than twice --
/// see `HomeLANVerifier`'s doc comment for how the underlying check itself works.
///
/// Exists specifically to prevent a repeat of the reload-storm incident from 2026-08-17: the first
/// version of the home-LAN shortcut re-verified live on every single tick and switched the
/// DNS/proxy target the moment that one tick's result flipped -- and switching the target is a
/// state CHANGE `EnforcementLoop` reacts to by triggering a full `PFBlocker` firewall reload on the
/// very next tick. A transient failure of one TLS handshake (a single slow round-trip past its
/// timeout, nothing actually wrong with the network) must never by itself flip the whole Mac's
/// DNS/proxy configuration and reload the firewall -- repeated over hours, that reload-on-every-flap
/// pattern is the suspected cause of a severe, escalating connectivity outage that night.
///
/// Requires `requiredConsecutiveSamples` consecutive readings in the SAME (opposite-of-current)
/// direction before the exposed state actually changes -- a lone outlier reading, in either
/// direction, is absorbed and never reaches a caller.
enum HomeLANState {
    private static let requiredConsecutiveSamples = 3

    private static var current = false
    private static var consecutiveOpposite = 0

    /// Runs the live check and returns the DEBOUNCED state, which may differ from this sample's own
    /// raw result -- callers should treat the return value as "the current stable answer", not "what
    /// just happened this tick". Call at most once per enforcement tick (see `EnforcementLoop`); the
    /// underlying check is a real network round-trip, not free.
    @discardableResult
    static func sample() -> Bool {
        let raw = HomeLANVerifier.verify(
            ip: FocusLockConstants.homeLANHost, hostname: FocusLockConstants.defaultCloudFilterHost
        )
        if raw == current {
            consecutiveOpposite = 0
        } else {
            consecutiveOpposite += 1
            if consecutiveOpposite >= requiredConsecutiveSamples {
                current = raw
                consecutiveOpposite = 0
            }
        }
        return current
    }
}
