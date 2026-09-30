import Foundation
import NetWorthCore
import Security

enum SelfTest {
    static func run() -> Int32 {
        var ok = true
        func check(_ name: String, _ pass: Bool, _ detail: String = "") {
            print("\(pass ? "PASS" : "FAIL") \(name) \(detail)")
            if !pass { ok = false }
        }

        let group = SharedIds.appGroup()
        let keychainGroup = SharedIds.keychainGroup()
        check("Info.plist NWAppGroup expands", group?.isEmpty == false && group?.contains("$") == false, group ?? "nil")
        check("Info.plist NWKeychainGroup expands", keychainGroup?.isEmpty == false && keychainGroup?.contains("$") == false, keychainGroup ?? "nil")

        let container = group.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
        check("App Group container exists", container != nil, container?.path ?? "nil")

        let defaults = group.flatMap { UserDefaults(suiteName: $0) }
        defaults?.set("hello", forKey: "selftest")
        check("group UserDefaults round trip", defaults?.string(forKey: "selftest") == "hello")
        defaults?.removeObject(forKey: "selftest")

        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.isym444.networth.selftest",
            kSecAttrAccount as String: "selftest",
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let keychainGroup { query[kSecAttrAccessGroup as String] = keychainGroup }
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = Data("selftest-key".utf8)
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        check("Keychain group write", addStatus == errSecSuccess, "status \(addStatus)")
        var read = query
        read[kSecReturnData as String] = true
        var out: CFTypeRef?
        let readStatus = SecItemCopyMatching(read as CFDictionary, &out)
        check("Keychain group read", readStatus == errSecSuccess && (out as? Data).flatMap { String(data: $0, encoding: .utf8) } == "selftest-key", "status \(readStatus)")
        SecItemDelete(query as CFDictionary)

        print(ok ? "SELF-TEST OK" : "SELF-TEST FAILED")
        return ok ? 0 : 1
    }
}
