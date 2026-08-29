import SwiftUI

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
