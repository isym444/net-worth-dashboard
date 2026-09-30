import Foundation
import Security

/// One JSON file holding the last good API response. Writes are atomic (temp file + rename) so the widget never reads a
/// half-written cache, and a missing or corrupt file loads as empty instead of throwing.
public final class HistoryCache {
    private let fileURL: URL

    public init(fileURL: URL) { self.fileURL = fileURL }

    public func load() -> CacheSnapshot {
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = try? JSONDecoder().decode(CacheSnapshot.self, from: data) else { return CacheSnapshot() }
        return snapshot
    }

    public func save(_ snapshot: CacheSnapshot) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(snapshot).write(to: fileURL, options: .atomic)
    }
}

/// Where the access key lives (the Keychain in the real app; in memory in tests).
public protocol SecretStore {
    func loadKey() -> String
    func saveKey(_ key: String) throws
}

/// URL settings and the last sync status live in shared `UserDefaults` (an App Group suite); the key lives in `SecretStore`.
public final class SettingsStore {
    private let defaults: UserDefaults
    private let secrets: SecretStore

    private enum K {
        static let url = "base_url", web = "web_url", ok = "last_ok", err = "last_err", rejected = "key_rejected"
    }

    public init(defaults: UserDefaults, secrets: SecretStore) {
        self.defaults = defaults
        self.secrets = secrets
    }

    public func load() -> AppSettings {
        AppSettings(baseUrl: defaults.string(forKey: K.url) ?? "", apiKey: secrets.loadKey(), webUrl: defaults.string(forKey: K.web) ?? "")
    }

    public func save(_ s: AppSettings) throws {
        try secrets.saveKey(s.apiKey)
        defaults.set(s.baseUrl, forKey: K.url)
        defaults.set(s.webUrl, forKey: K.web)
    }

    public func status() -> SyncStatus {
        SyncStatus(lastSuccessAt: Int64(defaults.object(forKey: K.ok) as? Int ?? 0),
                   lastError: defaults.string(forKey: K.err), keyRejected: defaults.bool(forKey: K.rejected))
    }

    public func recordSync(_ result: SyncResult, at ms: Int64) {
        switch result {
        case .failed(let error):
            defaults.set(error.userMessage, forKey: K.err)
            defaults.set(error.rejectsKey, forKey: K.rejected)
        case .updated:
            defaults.set(Int(ms), forKey: K.ok)
            defaults.removeObject(forKey: K.err)
            defaults.removeObject(forKey: K.rejected)
        }
    }

    /// A source for the current settings, or nil when not configured / the URL is invalid.
    public func source(session: URLSession = ApiClient.defaultSession) -> HistorySource? {
        let s = load()
        guard s.isConfigured, let url = UrlPolicy.parse(s.baseUrl) else { return nil }
        return ApiClient(baseUrl: url, apiKey: s.apiKey, session: session)
    }
}

/// The key in the data-protection Keychain under a shared access group, so the app and the widget extension both read it.
public final class KeychainSecretStore: SecretStore {
    private let service: String
    private let accessGroup: String?

    public init(service: String, accessGroup: String?) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query() -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "api_key",
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let accessGroup { q[kSecAttrAccessGroup as String] = accessGroup }
        return q
    }

    public func loadKey() -> String {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public func saveKey(_ key: String) throws {
        SecItemDelete(query() as CFDictionary)
        if key.isEmpty { return }
        var q = query()
        q[kSecValueData as String] = Data(key.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(q as CFDictionary, nil)
        if status != errSecSuccess { throw ApiError.storage("Keychain error \(status)") }
    }
}
