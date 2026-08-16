// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FocusLock",
    platforms: [.macOS(.v13)],
    targets: [
        // Shared XPC protocol + Codable models used by both the GUI app and the daemon.
        .target(name: "FocusLockShared"),

        // Root LaunchDaemon: owns the block state, enforces it, and is the only thing that can
        // grant an early end to a session.
        .executableTarget(
            name: "FocusLockHelperd",
            dependencies: ["FocusLockShared"]
        ),

        // SwiftUI GUI app run under your normal (Standard) account.
        .executableTarget(
            name: "FocusLock",
            dependencies: ["FocusLockShared"]
        ),

        // Command-line override tool, meant to be run from the Guardian admin account.
        .executableTarget(
            name: "focuslockctl",
            dependencies: ["FocusLockShared"]
        ),

        // Independent LaunchDaemon whose only job is noticing FocusLockHelperd got unloaded (e.g.
        // `sudo launchctl bootout`) and re-bootstrapping it -- detects and reports, doesn't
        // prevent; see GUARDIAN_SETUP.md §5. Deliberately tiny: no daemon/enforcement logic of its
        // own, so killing FocusLockHelperd can't also kill this.
        .executableTarget(
            name: "FocusLockWatchdog",
            dependencies: ["FocusLockShared"]
        ),

        // Covers the parts of FocusLockShared where a silent bug is a security bug rather than a
        // visible malfunction: passcode verification, and the encode/decode split that keeps the
        // passcode digest on disk but out of every `getStatus` reply.
        .testTarget(
            name: "FocusLockSharedTests",
            dependencies: ["FocusLockShared"]
        ),
    ]
)
