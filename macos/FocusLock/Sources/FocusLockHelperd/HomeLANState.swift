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
/// Requires `requiredConsecutiveSamples` consecutive readings in the same direction before the
/// exposed state actually changes -- a lone outlier reading, in either direction, is absorbed and
/// never reaches a caller.
enum HomeLANState {
    private static let requiredConsecutiveSamples = 3
    // Same absolute-time backstop as `ProxyEnforcer.debouncedReachable`, and for the same reason:
    // being stuck reporting "on home LAN" long after that stopped being true points DNS/proxy at
    // a LAN-only address from off the LAN, which is exactly the kind of silent, total-outage hang
    // this whole debounce family exists to prevent -- not merely a missed optimization the way
    // being stuck at "false" would be. One-directional on purpose: only forces true -> false.
    private static let maxStaleOnLANInterval: TimeInterval = 90

    private static var current = false
    private static var consecutiveSame = 0
    private static var consecutiveOpposite = 0
    private static var lastConfirmedOnLANAt: Date?

    /// Runs the live check and returns the DEBOUNCED state, which may differ from this sample's own
    /// raw result -- callers should treat the return value as "the current stable answer", not "what
    /// just happened this tick". Call at most once per enforcement tick (see `EnforcementLoop`); the
    /// underlying check is a real network round-trip, not free.
    @discardableResult
    static func sample() -> Bool {
        let raw = HomeLANVerifier.verify(
            ip: FocusLockConstants.homeLANHost, hostname: FocusLockConstants.defaultCloudFilterHost
        )
        // Streaks are counted against the RAW signal's own run, never against "does this sample
        // agree with the CURRENT debounced state" -- see `ProxyEnforcer.debouncedReachable`'s doc
        // comment (same bug, same fix, confirmed live 2026-09-02 there): the old version reset its
        // one shared counter to 0 whenever a sample agreed with `current`, so a signal that's
        // genuinely flapping rather than cleanly flipped (e.g. two-out-of-three readings opposite,
        // one agreeing, on repeat) could reset the streak before it ever reached the threshold and
        // never converge in either direction.
        if raw {
            consecutiveSame += 1
            consecutiveOpposite = 0
            lastConfirmedOnLANAt = Date()
        } else {
            consecutiveOpposite += 1
            consecutiveSame = 0
        }

        if !current, consecutiveSame >= requiredConsecutiveSamples {
            current = true
        } else if current, consecutiveOpposite >= requiredConsecutiveSamples {
            current = false
        }

        if current,
           let lastConfirmed = lastConfirmedOnLANAt,
           Date().timeIntervalSince(lastConfirmed) > maxStaleOnLANInterval {
            current = false
        }
        return current
    }
}
