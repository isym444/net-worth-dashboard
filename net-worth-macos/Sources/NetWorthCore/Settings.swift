import Foundation

public struct AppSettings: Equatable {
    public var baseUrl: String
    public var apiKey: String
    public var webUrl: String

    public static let defaultBaseUrl = "https://nwapi.isym.uk"

    public init(baseUrl: String = "", apiKey: String = "", webUrl: String = "") {
        self.baseUrl = baseUrl
        self.apiKey = apiKey
        self.webUrl = webUrl
    }

    public var isConfigured: Bool {
        !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// Outcome of the last sync attempt, for the widget's hint line and the settings window.
public struct SyncStatus: Equatable {
    public var lastSuccessAt: Int64
    public var lastError: String?
    public var keyRejected: Bool

    public init(lastSuccessAt: Int64 = 0, lastError: String? = nil, keyRejected: Bool = false) {
        self.lastSuccessAt = lastSuccessAt
        self.lastError = lastError
        self.keyRejected = keyRejected
    }
}

public enum UrlPolicy {
    private static let localHosts: Set<String> = ["localhost", "127.0.0.1", "10.0.2.2"]

    /// Normalises a user-typed URL. HTTPS is required except for local dev hosts. The result always has a path ("https://x/").
    public static func parse(_ input: String) -> URL? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if !s.contains("://") { s = "https://" + s }
        guard var comps = URLComponents(string: s),
              let scheme = comps.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = comps.host, !host.isEmpty else { return nil }
        if scheme == "http" && !localHosts.contains(host.lowercased()) { return nil }
        if comps.path.isEmpty { comps.path = "/" }
        return comps.url
    }
}

public enum Field: Hashable { case baseUrl, apiKey, webUrl }

public enum Validation: Equatable {
    case valid(AppSettings)
    case invalid([Field: String])
}

public enum SettingsValidator {
    public static func validate(baseUrl: String, apiKey: String, webUrl: String) -> Validation {
        var errors: [Field: String] = [:]

        let base = UrlPolicy.parse(baseUrl)
        if base == nil { errors[.baseUrl] = "Enter a valid address. https is required (for example https://nwapi.example.com)." }

        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if key.isEmpty {
            errors[.apiKey] = "Paste the dashboard access key."
        } else if key.contains(where: { $0.isWhitespace }) {
            errors[.apiKey] = "The key must not contain spaces."
        }

        let web = webUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let webParsed = web.isEmpty ? nil : UrlPolicy.parse(web)
        if !web.isEmpty && webParsed == nil { errors[.webUrl] = "Enter a valid https address, or leave blank." }

        guard errors.isEmpty, let base else { return .invalid(errors) }
        return .valid(AppSettings(baseUrl: base.absoluteString, apiKey: key, webUrl: webParsed?.absoluteString ?? ""))
    }
}
