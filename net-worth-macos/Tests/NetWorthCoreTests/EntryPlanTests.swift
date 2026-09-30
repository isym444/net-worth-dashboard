import XCTest
@testable import NetWorthCore

final class EntryPlanTests: XCTestCase {
    private let start = ISO8601DateFormatter().date(from: "2026-09-30T12:00:00Z")!

    func testTwelveEntriesThirtyMinutesApart() {
        let d = EntryPlan.dates(from: start)
        XCTAssertEqual(d.count, 12)
        XCTAssertEqual(d[0], start)
        XCTAssertEqual(d[1].timeIntervalSince(d[0]), 1_800)
        XCTAssertEqual(d[11].timeIntervalSince(start), 11 * 1_800)
    }

    func testUpdatedTextAdvancesAndTheStaleHintAppearsOnItsOwn() {
        let fetchedAt = Int64((start.timeIntervalSince1970 - 5 * 3_600 - 40 * 60) * 1000)      // 5h40m old at the first entry
        let history = HistoryResponse(snapshots: [Snapshot(id: 1, timestamp: "2026-09-30T07:00:00Z", currency: "GBP", grandTotal: 1_000)])
        let states = EntryPlan.states(
            cache: CacheSnapshot(fetchedAt: fetchedAt, history: history), config: WidgetConfig(),
            settings: AppSettings(baseUrl: "https://x/", apiKey: "k"), status: SyncStatus(), start: start, calendar: T.calendar)

        func ready(_ i: Int) -> WidgetState.Ready {
            guard case .ready(let r) = states[i].state else { fatalError() }
            return r
        }
        XCTAssertEqual(ready(0).updated, "5時間前に更新")
        XCTAssertNil(ready(0).hint)
        XCTAssertEqual(ready(1).updated, "6時間前に更新")           // 30 minutes later the data is over 6 hours old
        XCTAssertEqual(ready(1).hint, "古いデータ — サーバーに接続できません")
        XCTAssertEqual(ready(11).updated, "11時間前に更新")
    }
}
