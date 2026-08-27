import XCTest
@testable import FocusLockShared

/// `HostnameValidator` is what stops a caller-supplied domain from becoming attacker-chosen lines
/// in root-owned `/etc/hosts` (see `XPCService.addBlockedDomain`, which is ungated by design --
/// adding a block is protection-increasing, so anything may call it). Since adding is open, the
/// validation is the only thing standing between that method and a hosts-file injection.
final class HostnameValidatorTests: XCTestCase {

    func testAcceptsOrdinaryHostnames() {
        for host in ["example.com", "www.example.com", "a.b.c.d.example.co.uk",
                     "xn--80ak6aa92e.com", "my-host123.example", "1.2.3.4"] {
            XCTAssertTrue(HostnameValidator.isValidHostname(host), "rejected valid host \(host)")
        }
    }

    func testRejectsNewlineInjection() {
        // The actual attack: a trailing newline plus a second line would add an entry the Guardian
        // never asked for -- e.g. redirecting icloud.com -- once written into /etc/hosts.
        XCTAssertFalse(HostnameValidator.isValidHostname("x\n1.2.3.4 icloud.com"))
        XCTAssertFalse(HostnameValidator.isValidHostname("x\r\n1.2.3.4 icloud.com"))
        XCTAssertFalse(HostnameValidator.isValidHostname("evil.com\n127.0.0.1 bank.example"))
    }

    func testRejectsWhitespaceAndSeparators() {
        for host in ["two words", "tab\there", "a\u{0000}b", "a;b", "a|b", "a/b", "a\\b", "a:b"] {
            XCTAssertFalse(HostnameValidator.isValidHostname(host), "accepted invalid host \(host.debugDescription)")
        }
    }

    func testRejectsMalformedLabels() {
        XCTAssertFalse(HostnameValidator.isValidHostname(""))
        XCTAssertFalse(HostnameValidator.isValidHostname("."))
        XCTAssertFalse(HostnameValidator.isValidHostname("a..b"))
        XCTAssertFalse(HostnameValidator.isValidHostname(".leading"))
        XCTAssertFalse(HostnameValidator.isValidHostname("trailing."))
        XCTAssertFalse(HostnameValidator.isValidHostname("-leadinghyphen.com"))
        XCTAssertFalse(HostnameValidator.isValidHostname("trailinghyphen-.com"))
    }

    func testEnforcesLengthLimits() {
        XCTAssertTrue(HostnameValidator.isValidHostname(String(repeating: "a", count: 63) + ".com"))
        XCTAssertFalse(HostnameValidator.isValidHostname(String(repeating: "a", count: 64) + ".com"))
        let tooLong = Array(repeating: String(repeating: "a", count: 50), count: 6).joined(separator: ".")
        XCTAssertFalse(HostnameValidator.isValidHostname(tooLong))
    }

    func testRejectsNonASCII() {
        // Punycode is the supported way to express these; raw Unicode would not round-trip
        // predictably through the resolver.
        XCTAssertFalse(HostnameValidator.isValidHostname("пример.com"))
        XCTAssertFalse(HostnameValidator.isValidHostname("exa\u{00AD}mple.com"))
    }
}
