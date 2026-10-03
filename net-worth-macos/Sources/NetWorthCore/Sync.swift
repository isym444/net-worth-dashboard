import Foundation

public enum SyncResult: Equatable {
    case updated
    case failed(ApiError)
}

/// Refreshes the cache from the API. On *any* failure the cache is left exactly as it was, so the widget keeps rendering
/// the last good data.
public struct Sync {
    private let cache: HistoryCache
    private let source: () -> HistorySource?
    private let clock: () -> Int64
    private let onResult: (SyncResult, Int64) -> Void

    public init(
        cache: HistoryCache,
        source: @escaping () -> HistorySource?,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        onResult: @escaping (SyncResult, Int64) -> Void = { _, _ in }
    ) {
        self.cache = cache
        self.source = source
        self.clock = clock
        self.onResult = onResult
    }

    @discardableResult
    public func refresh() async -> SyncResult {
        let result = await attempt()
        onResult(result, clock())
        return result
    }

    private func attempt() async -> SyncResult {
        guard let api = source() else { return .failed(.notConfigured) }
        do {
            let fresh = try await api.history()
            // Keep every snapshot: the change lines, all-time high and trend chart need the whole history.
            try cache.save(CacheSnapshot(fetchedAt: clock(), history: fresh))
            return .updated
        } catch let error as ApiError {
            return .failed(error)
        } catch {
            return .failed(.storage(error.localizedDescription))
        }
    }
}
