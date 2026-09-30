import SwiftUI

@main
struct NetWorthApp: App {
    init() {
        // `NetWorth --self-test` checks the App Group and Keychain group from the command line, then exits.
        if CommandLine.arguments.contains("--self-test") { exit(SelfTest.run()) }
    }

    var body: some Scene {
        WindowGroup("純資産") { SettingsView() }
            .windowResizability(.contentSize)
    }
}
