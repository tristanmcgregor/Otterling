import Foundation
import Network

/// Confirms a candidate IP is genuinely this project's filter-server before `DNSEnforcer` or
/// `ProxyEnforcer` trust it as a same-host substitute for `defaultCloudFilterHost`'s normal
/// hostname resolution (see `FocusLockConstants.homeLANHost`'s doc comment for why that
/// substitution exists at all).
///
/// A bare TCP connect is NOT authentication: anyone who can put a device at the same private IP on
/// ANY network this Mac joins (a coffee shop, a hotel, an attacker's own hotspot) would make that
/// check pass and get this Mac's DNS queries and proxied web traffic handed to them in the clear --
/// a straightforward hijack, and strictly worse than doing nothing. Instead this performs a real TLS
/// handshake to the candidate IP with SNI set to the pinned hostname, using the system's default
/// certificate validation (chain-of-trust + hostname-vs-SNI) completely unmodified. Only whoever
/// actually holds the private key for a publicly-trusted certificate covering that hostname -- i.e.
/// the real filter-server, which Caddy terminates HTTPS for -- can make this succeed. This is the
/// same trust model every HTTPS site already relies on; nothing here weakens or bypasses it.
public enum HomeLANVerifier {
    /// Returns true only if a genuine TLS handshake for `hostname` succeeds against `ip`. Deliberately
    /// does not accept a `verify_block` override or any other way to relax validation -- the whole
    /// point is that this must fail for anyone who isn't the real server.
    public static func verify(ip: String, hostname: String, port: UInt16 = 443, timeout: TimeInterval = 3) -> Bool {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return false }

        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_tls_server_name(tlsOptions.securityProtocolOptions, hostname)

        let parameters = NWParameters(tls: tlsOptions, tcp: .init())
        let connection = NWConnection(host: NWEndpoint.Host(ip), port: nwPort, using: parameters)
        let semaphore = DispatchSemaphore(value: 0)
        var verified = false
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                verified = true
                semaphore.signal()
            case .failed, .cancelled:
                verified = false
                semaphore.signal()
            default:
                break
            }
        }
        connection.start(queue: .global())
        _ = semaphore.wait(timeout: .now() + timeout)
        connection.cancel()
        return verified
    }
}
