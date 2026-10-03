import Foundation

public enum ApiError: Error, Equatable {
    case notConfigured
    case http(status: Int, detail: String)
    case network(String)
    case decoding
    case storage(String)

    public var userMessage: String {
        switch self {
        case .notConfigured: return "Enter the API URL and access key first"
        case .http(let status, _):
            return (status == 401 || status == 403)
                ? "Access key rejected (HTTP \(status)) — check the dashboard access key"
                : "Server error (HTTP \(status))"
        case .network: return "Can't reach the server — check the URL and your connection"
        case .decoding: return "Unexpected response — is this the Net Worth API URL?"
        case .storage: return "Couldn't save data on this Mac — is the disk full?"
        }
    }

    /// True when the server said the key is wrong (as opposed to being down or unreachable).
    public var rejectsKey: Bool {
        if case .http(let status, _) = self { return status == 401 || status == 403 }
        return false
    }
}

/// What `Sync` needs from the network; lets tests substitute a fake.
public protocol HistorySource {
    func history() async throws -> HistoryResponse
}

/// Talks to net-worth-service. Deliberately does no logging: the X-Api-Key header must never reach a log.
public struct ApiClient: HistorySource {
    private let baseUrl: URL
    private let key: String
    private let session: URLSession

    public init(baseUrl: URL, apiKey: String, session: URLSession = ApiClient.defaultSession) {
        self.baseUrl = baseUrl
        self.key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.session = session
    }

    public static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 20
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    public func history() async throws -> HistoryResponse {
        // A pasted key containing control characters can never be a valid header value.
        if key.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }) { throw ApiError.notConfigured }
        var request = URLRequest(url: baseUrl.appendingPathComponent("api/v1/history"))
        request.httpMethod = "GET"
        request.setValue(key, forHTTPHeaderField: "X-Api-Key")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw ApiError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw ApiError.decoding }
        guard (200..<300).contains(http.statusCode) else {
            throw ApiError.http(status: http.statusCode, detail: Self.errorDetail(data))
        }
        do {
            return try JSONDecoder().decode(HistoryResponse.self, from: data)
        } catch {
            throw ApiError.decoding
        }
    }

    /// The auth filter answers `{"error": ...}`; Spring's default error body uses `message`.
    static func errorDetail(_ data: Data) -> String {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        return (obj["message"] as? String) ?? (obj["error"] as? String) ?? ""
    }
}
