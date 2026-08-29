import SwiftUI

// Test commit: verifying versionCode increments to 4 on this push.
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
