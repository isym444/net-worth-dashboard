import Foundation
import NetWorthCore

enum SelfTest {
    static func run() -> Int32 {
        var ok = true
        func check(_ name: String, _ pass: Bool, _ detail: String = "") {
            print("\(pass ? "PASS" : "FAIL") \(name) \(detail)")
            if !pass { ok = false }
        }

        let group = SharedIds.appGroup()
        check("Info.plist NWAppGroup expands", group?.isEmpty == false && group?.contains("$") == false, group ?? "nil")

        let container = group.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
        check("App Group container exists", container != nil, container?.path ?? "nil")

        let defaults = group.flatMap { UserDefaults(suiteName: $0) }
        defaults?.set("hello", forKey: "selftest")
        check("group UserDefaults round trip", defaults?.string(forKey: "selftest") == "hello")
        defaults?.removeObject(forKey: "selftest")

        // A sandbox without a valid App Group entitlement denies writes here, so this is the check that actually
        // proves the app and the widget can share the key file.
        if let container {
            let url = container.appendingPathComponent("selftest-key")
            let store = FileSecretStore(url: url)
            let saved = (try? store.saveKey("selftest-key")) != nil
            check("group container key file write", saved, url.path)
            check("group container key file read", store.loadKey() == "selftest-key")
            let mode = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.posixPermissions] as? NSNumber)?.intValue
            check("key file is owner-only (0600)", mode == 0o600, mode.map { String($0, radix: 8) } ?? "nil")
            try? store.saveKey("")
        }

        print(ok ? "SELF-TEST OK" : "SELF-TEST FAILED")
        return ok ? 0 : 1
    }
}
