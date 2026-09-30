import XCTest
@testable import NetWorthCore

final class ModelsTests: XCTestCase {
    /// Shape of the real /api/v1/history response, including fields the widget ignores.
    private let realistic = """
    {"snapshots":[
      {"id":1,"timestamp":"2026-09-28T07:00:00Z","currency":"GBP","t212Total":100.5,"cryptoTotal":1.0,
       "chaseTotal":null,"amexDebtTotal":null,"amountLeft":null,"grandTotal":183000.5,
       "grandTotalEur":null,"aiCommentary":"Spending is up"},
      {"id":2,"timestamp":"2026-09-29T07:00:36.680923Z","currency":"GBP","grandTotal":184320.25,
       "someFutureField":{"a":1}}],
     "expectedNetWorthAt40":900000.0,"expectedNetWorthAt50":1500000.0,
     "expectedNetWorthAt60":2500000.0,"expectedNetWorthAt65":3500000.0,
     "expectedNetWorthWithContributionsAt40":1000000.0,"expectedNetWorthWithContributionsAt50":1700000.0,
     "expectedNetWorthWithContributionsAt60":2900000.0,"expectedNetWorthWithContributionsAt65":4100000.0,
     "estimatedNetWorth1y":200000.0,"monthlyContribution":1000.0}
    """

    private func decode(_ json: String) throws -> HistoryResponse {
        try JSONDecoder().decode(HistoryResponse.self, from: Data(json.utf8))
    }

    func testDecodesRealisticResponseAndIgnoresUnknownKeys() throws {
        let h = try decode(realistic)
        XCTAssertEqual(h.snapshots.count, 2)
        XCTAssertEqual(h.snapshots.last!.grandTotal, 184320.25)
        XCTAssertEqual(h.snapshots.last!.currency, "GBP")
        XCTAssertEqual(h.snapshots.last!.timestamp, "2026-09-29T07:00:36.680923Z")
        XCTAssertEqual(h.expectedNetWorthAt40, 900000)
        XCTAssertEqual(h.expectedNetWorthWithContributionsAt65, 4100000)
        XCTAssertEqual(h.estimatedNetWorth1y, 200000)
        XCTAssertEqual(h.monthlyContribution, 1000)
    }

    func testNullOrMissingProjectionFieldsDecodeAsNil() throws {
        let h = try decode(#"{"snapshots":[{"grandTotal":1.0}],"estimatedNetWorth1y":null,"monthlyContribution":null}"#)
        XCTAssertNil(h.expectedNetWorthAt40)
        XCTAssertNil(h.expectedNetWorthWithContributionsAt65)
        XCTAssertNil(h.estimatedNetWorth1y)
        XCTAssertNil(h.monthlyContribution)
        XCTAssertEqual(h.snapshots.first!.currency, "GBP")     // a missing currency falls back to the default
    }

    func testBrandNewDatabaseDecodes() throws {
        XCTAssertTrue(try decode(#"{"snapshots":[]}"#).snapshots.isEmpty)
    }

    func testAJsonBodyWithoutSnapshotsIsNotAHistoryResponse() {
        // {} or {"status":"ok"} means the URL points at some other service; it must not decode as an empty history.
        XCTAssertThrowsError(try decode("{}"))
        XCTAssertThrowsError(try decode(#"{"status":"ok"}"#))
    }

    func testCacheSnapshotRoundTrips() throws {
        let original = CacheSnapshot(
            fetchedAt: 1_790_000_000_000,
            history: HistoryResponse(
                snapshots: [Snapshot(id: 2, timestamp: "t", currency: "EUR", grandTotal: 5.5)], estimatedNetWorth1y: 9))
        let data = try JSONEncoder().encode(original)
        XCTAssertEqual(try JSONDecoder().decode(CacheSnapshot.self, from: data), original)
    }

    func testEmptyCacheHasNoHistory() {
        let c = CacheSnapshot()
        XCTAssertEqual(c.fetchedAt, 0)
        XCTAssertNil(c.history)
    }
}
