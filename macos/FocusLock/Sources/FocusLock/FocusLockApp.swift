import SwiftUI

// Test commit: verifying the auto-publish pipeline completes end-to-end.
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
