import XCTest
@testable import FocusLockShared

/// Covers the `/etc/hosts` parsing, where a silent bug takes the machine off the network rather
/// than producing a visible error.
final class HostsFileBlockTests: XCTestCase {
    private let begin = FocusLockConstants.hostsMarkerBegin
    private let end = FocusLockConstants.hostsMarkerEnd
    private let legacyBegin = FocusLockConstants.legacyHostsMarkerBegin
    private let legacyEnd = FocusLockConstants.legacyHostsMarkerEnd

    private let systemLines = """
    ##
    # Host Database
    ##
    127.0.0.1\tlocalhost
    255.255.255.255\tbroadcasthost
    ::1             localhost
    """

    // MARK: - The truncation bug

    func testMissingEndMarkerReturnsNilInsteadOfEatingTheRestOfTheFile() {
        // This is the regression test. Previously everything after BEGIN was returned as
        // "stripped", so localhost and every user-added line below it were silently deleted.
        let content = systemLines + "\n" + begin + "\n127.0.0.1 blocked.example\n"
        XCTAssertNil(HostsFileBlock.strip(from: content))
    }

    func testMissingEndMarkerDoesNotDropUserLinesBelowTheBlock() {
        let content = begin + "\n127.0.0.1 blocked.example\n10.0.0.5 my-nas.local\n"
        XCTAssertNil(
            HostsFileBlock.strip(from: content),
            "a malformed block must be reported, not silently rewritten -- my-nas.local would be lost"
        )
    }

    func testLegacyBeginWithoutItsEndIsAlsoNil() {
        let content = systemLines + "\n" + legacyBegin + "\n127.0.0.1 blocked.example\n"
        XCTAssertNil(HostsFileBlock.strip(from: content))
    }

    // MARK: - Normal operation

    func testStripRemovesOnlyOurOwnBlock() {
        let content = systemLines + "\n\n" + begin + "\n127.0.0.1 a.example\n::1 a.example\n" + end + "\n"
        let stripped = HostsFileBlock.strip(from: content)
        XCTAssertNotNil(stripped)
        XCTAssertFalse(stripped!.contains("a.example"))
        XCTAssertTrue(stripped!.contains("127.0.0.1\tlocalhost"))
        XCTAssertTrue(stripped!.contains("broadcasthost"))
    }

    func testStripIsANoOpWhenWeOwnNothing() {
        XCTAssertEqual(HostsFileBlock.strip(from: systemLines), systemLines)
    }

    func testStripHandlesTheLegacyBrandedMarkers() {
        // An upgrade from a FocusLock-branded build must clean up its own block rather than orphan
        // it under marker text the current build no longer matches.
        let content = systemLines + "\n" + legacyBegin + "\n127.0.0.1 old.example\n" + legacyEnd + "\n"
        let stripped = HostsFileBlock.strip(from: content)
        XCTAssertNotNil(stripped)
        XCTAssertFalse(stripped!.contains("old.example"))
        XCTAssertTrue(stripped!.contains("localhost"))
    }

    func testStripIsIdempotent() {
        let content = systemLines + "\n" + begin + "\n127.0.0.1 a.example\n" + end + "\n"
        let once = HostsFileBlock.strip(from: content)
        XCTAssertNotNil(once)
        XCTAssertEqual(HostsFileBlock.strip(from: once!), once)
    }

    func testUserLinesBelowOurBlockSurviveANormalStrip() {
        let content = begin + "\n127.0.0.1 a.example\n" + end + "\n10.0.0.5 my-nas.local\n"
        let stripped = HostsFileBlock.strip(from: content)
        XCTAssertNotNil(stripped)
        XCTAssertTrue(stripped!.contains("my-nas.local"))
        XCTAssertFalse(stripped!.contains("a.example"))
    }

    // MARK: - Write side

    func testLinesCoverBothFamiliesAndTheWwwHost() {
        XCTAssertEqual(HostsFileBlock.lines(for: "example.com"), [
            "127.0.0.1 example.com",
            "127.0.0.1 www.example.com",
            "::1 example.com",
            "::1 www.example.com",
        ])
    }

    func testAWrittenBlockStripsBackToTheOriginal() {
        // The write and removal paths must be exact inverses, which is why they live in one type.
        var lines = [begin]
        for domain in ["a.example", "b.example"] { lines.append(contentsOf: HostsFileBlock.lines(for: domain)) }
        lines.append(end)
        let content = systemLines + "\n\n" + lines.joined(separator: "\n") + "\n"
        XCTAssertEqual(HostsFileBlock.strip(from: content)?.trimmingCharacters(in: .newlines), systemLines)
    }
}
