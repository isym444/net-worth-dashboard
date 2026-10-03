import Foundation

/// The settings store and cache the app and the widget share. If the App Group is unavailable (a signing problem) it falls
/// back to a private temporary location, so the widget renders "not configured" instead of crashing.
public struct SharedEnvironment {
    public let settings: SettingsStore
    public let cache: HistoryCache

    public init(settings: SettingsStore, cache: HistoryCache) {
        self.settings = settings
        self.cache = cache
    }

    public static func make(bundle: Bundle = .main) -> SharedEnvironment {
        make(appGroup: SharedIds.appGroup(bundle), keychainGroup: SharedIds.keychainGroup(bundle))
    }

    public static func make(appGroup: String?, keychainGroup: String?) -> SharedEnvironment {
        let container = appGroup.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
        let defaults = appGroup.flatMap { UserDefaults(suiteName: $0) } ?? UserDefaults(suiteName: "com.isym444.networth.fallback")!
        let dir = container ?? FileManager.default.temporaryDirectory.appendingPathComponent("networth-fallback")
        return SharedEnvironment(
            settings: SettingsStore(defaults: defaults, secrets: KeychainSecretStore(service: SharedIds.keychainService, accessGroup: keychainGroup)),
            cache: HistoryCache(fileURL: dir.appendingPathComponent("cache.json")))
    }
}
