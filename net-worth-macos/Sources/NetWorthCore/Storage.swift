import Foundation

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

/// Where the access key lives (a file in the App Group container in the real app; in memory in tests).
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

/// The key in an owner-only (0600) file inside the App Group container, so the app and the widget extension both read it.
/// The Keychain's shared access group needs a provisioning profile, which a free Apple ID only holds for 7 days; a file
/// in the group container needs none, so the build keeps working indefinitely. The container directory is itself 0700.
public final class FileSecretStore: SecretStore {
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func loadKey() -> String {
        guard let data = try? Data(contentsOf: url) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public func saveKey(_ key: String) throws {
        let fm = FileManager.default
        if key.isEmpty {
            if fm.fileExists(atPath: url.path) { try fm.removeItem(at: url) }
            return
        }
        do {
            try Data(key.utf8).write(to: url, options: .atomic)
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        } catch {
            throw ApiError.storage("Couldn't write the key file: \(error.localizedDescription)")
        }
    }
}
