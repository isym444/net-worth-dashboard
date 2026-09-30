import XCTest
@testable import NetWorthCore

final class SyncTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    private func cache() -> HistoryCache { HistoryCache(fileURL: dir.appendingPathComponent("cache.json")) }

    private struct FakeSource: HistorySource {
        let result: () throws -> HistoryResponse
        func history() async throws -> HistoryResponse { try result() }
    }

    private func history(_ totals: Double...) -> HistoryResponse {
        HistoryResponse(snapshots: totals.enumerated().map { Snapshot(id: Int64($0), grandTotal: $1) }, estimatedNetWorth1y: 9)
    }

    func testSuccessKeepsTheWholeHistoryAndStampsFetchTime() async {
        let c = cache()
        var results: [(SyncResult, Int64)] = []
        let sync = Sync(cache: c, source: { FakeSource { self.history(1, 2, 3, 4) } }, clock: { 777 }, onResult: { results.append(($0, $1)) })

        let r = await sync.refresh()

        XCTAssertEqual(r, .updated)
        let saved = c.load()
        XCTAssertEqual(saved.fetchedAt, 777)
        XCTAssertEqual(saved.history?.snapshots.map { $0.grandTotal }, [1, 2, 3, 4])
        XCTAssertEqual(saved.history?.estimatedNetWorth1y, 9)
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].0, .updated)
        XCTAssertEqual(results[0].1, 777)
    }

    func testNotConfiguredFailsWithoutTouchingTheCache() async {
        let c = cache()
        let r = await Sync(cache: c, source: { nil }).refresh()
        XCTAssertEqual(r, .failed(.notConfigured))
        XCTAssertNil(c.load().history)
    }

    func testAFailureLeavesTheCacheAlone() async throws {
        let c = cache()
        try c.save(CacheSnapshot(fetchedAt: 1, history: history(5)))
        for error in [ApiError.network("offline"), .http(status: 401, detail: ""), .decoding] {
            let r = await Sync(cache: c, source: { FakeSource { throw error } }).refresh()
            XCTAssertEqual(r, .failed(error))
            XCTAssertEqual(c.load().history?.snapshots.first?.grandTotal, 5)
            XCTAssertEqual(c.load().fetchedAt, 1)
        }
    }

    func testAnUnwritableCacheIsAStorageError() async throws {
        let blocker = dir.appendingPathComponent("blocker")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try "x".write(to: blocker, atomically: true, encoding: .utf8)        // a file where a directory must be
        let c = HistoryCache(fileURL: blocker.appendingPathComponent("cache.json"))
        let r = await Sync(cache: c, source: { FakeSource { self.history(1) } }).refresh()
        guard case .failed(.storage) = r else { return XCTFail("\(r)") }
    }
}
