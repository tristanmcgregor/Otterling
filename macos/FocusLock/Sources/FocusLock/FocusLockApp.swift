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
        .commands {
            CommandMenu("Test") {
                Button("Test Item") {}
            }
        }
    }
}
