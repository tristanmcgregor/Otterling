import SwiftUI

// Test commit: verifying the build-agent auto-publish pipeline end-to-end.
@main
struct FocusLockApp: App {
    init() {
        DaemonRegistrar.registerIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .windowResizability(.contentSize)
    }
}
