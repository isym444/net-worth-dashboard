import XCTest
@testable import NetWorthCore

final class PaydayTests: XCTestCase {
    private let cal = T.calendar

    private func next(_ y: Int, _ m: Int, _ d: Int) -> Date { Payday.next(after: T.date(y, m, d), calendar: cal) }
    private func days(_ y: Int, _ m: Int, _ d: Int) -> Int { Payday.daysUntilNext(from: T.date(y, m, d), calendar: cal) }

    func testBankHolidays2026() {
        XCTAssertEqual(Set(Payday.bankHolidays(year: 2026, calendar: cal)), [
            T.date(2026, 1, 1), T.date(2026, 4, 3), T.date(2026, 4, 6), T.date(2026, 5, 4),
            T.date(2026, 5, 25), T.date(2026, 8, 31), T.date(2026, 12, 25), T.date(2026, 12, 28),
        ])
    }

    func testChristmasOnASaturdayDoubleShifts() {
        // 2021: 25th is Saturday, 26th Sunday -> Mon 27th and Tue 28th.
        let h = Set(Payday.bankHolidays(year: 2021, calendar: cal))
        XCTAssertTrue(h.contains(T.date(2021, 12, 27)) && h.contains(T.date(2021, 12, 28)))
    }

    func testNextPaydayThisMonthWhenTheTwentyThirdIsAWorkingDay() {
        XCTAssertEqual(next(2026, 10, 1), T.date(2026, 10, 23))      // Friday
        XCTAssertEqual(days(2026, 9, 30), 23)
    }

    func testAWeekendPaydayMovesBackToFriday() {
        XCTAssertEqual(next(2026, 8, 1), T.date(2026, 8, 21))        // 23rd is a Sunday
        XCTAssertEqual(next(2027, 1, 1), T.date(2027, 1, 22))        // 23rd is a Saturday
    }

    func testOnPaydayItselfTheNextOneIsNextMonth() {
        XCTAssertEqual(next(2026, 10, 23), T.date(2026, 11, 23))
        XCTAssertEqual(days(2026, 10, 23), 31)
    }

    func testAfterPaydayRollsToNextMonthAndYear() {
        XCTAssertEqual(next(2026, 10, 24), T.date(2026, 11, 23))
        XCTAssertEqual(next(2026, 12, 24), T.date(2027, 1, 22))
    }

    func testTheDayBeforePaydayIsOneDay() { XCTAssertEqual(days(2026, 10, 22), 1) }

    func testTheWidgetShowsDaysToPayday() {
        let h = HistoryResponse(snapshots: [Snapshot(id: 0, timestamp: "2026-09-29T07:00:00Z", currency: "GBP", grandTotal: 1)])
        let now = ISO8601DateFormatter().date(from: "2026-09-30T12:00:00Z")!
        let state = WidgetModel.build(
            cache: CacheSnapshot(fetchedAt: Int64(now.timeIntervalSince1970 * 1000), history: h), config: WidgetConfig(),
            settings: AppSettings(baseUrl: "https://x/", apiKey: "k", webUrl: ""), status: SyncStatus(), now: now, calendar: cal)
        guard case .ready(let r) = state else { return XCTFail("expected ready") }
        XCTAssertEqual(r.daysToPayday, 23)
        XCTAssertEqual(r.payday.text, "給料日まで23日")
    }
}
