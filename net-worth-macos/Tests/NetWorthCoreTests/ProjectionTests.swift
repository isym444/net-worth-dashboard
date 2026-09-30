import XCTest
@testable import NetWorthCore

final class ProjectionTests: XCTestCase {
    private let today = T.date(2026, 9, 30)

    private func reach(_ current: Double, _ target: Double, _ contribution: Double) -> Reach {
        Projection.reach(current: current, target: target, monthlyContribution: contribution, today: today, calendar: T.calendar)
    }

    func testMatchesDashboardWithoutContributions() {
        XCTAssertEqual(reach(50_000, 100_000, 0), .inMonths(123, age: 40))
        XCTAssertEqual(reach(50_000, 100_000, 0).text(), "10年3か月 (40歳)")
        XCTAssertEqual(reach(184_320, 250_000, 0).text(), "4年7か月 (34歳)")
        XCTAssertEqual(reach(184_320, 1_000_000, 0).text(), "25年0か月 (55歳)")
    }

    func testMatchesDashboardWithContributions() {
        XCTAssertEqual(reach(50_000, 100_000, 1_000), .inMonths(36, age: 33))
        XCTAssertEqual(reach(50_000, 100_000, 1_000).text(), "3年0か月 (33歳)")
        XCTAssertEqual(reach(184_320, 250_000, 1_000).text(), "2年6か月 (32歳)")
        XCTAssertEqual(reach(184_320, 1_000_000, 1_500).text(), "15年4か月 (45歳)")
    }

    func testAlreadyReachedIncludesEqualAndAbove() {
        XCTAssertEqual(reach(100_000, 100_000, 0), .reached(age: 30))
        XCTAssertEqual(reach(250_000, 100_000, 0).text(), "達成 (30歳)")
    }

    func testJustUnderTheTargetTakesOneMonth() {
        XCTAssertEqual(reach(99_999.99, 100_000, 0).text(), "0年1か月 (30歳)")
    }

    func testNeverWhenNotReachableWithinTheCap() {
        XCTAssertEqual(reach(1_000, 1_000_000, 0), .never)
        XCTAssertEqual(reach(1_000, 1_000_000, 0).text(), "100年以上")
        XCTAssertEqual(reach(0, 100_000, 0).text(), "100年以上")
        XCTAssertNil(Projection.monthsToReach(current: 0, target: 100_000, monthlyContribution: 0))
    }

    func testNegativeNetWorthWithNoContributionNeverReaches() {
        XCTAssertEqual(reach(-5_000, 100_000, 0), .never)
    }

    func testNegativeNetWorthWithAContributionEventuallyReaches() {
        guard case .inMonths = reach(-5_000, 100_000, 1_000) else { return XCTFail("expected inMonths") }
    }

    func testMonthsToReachIsZeroWhenAlreadyThere() {
        XCTAssertEqual(Projection.monthsToReach(current: 100_000, target: 100_000, monthlyContribution: 0), 0)
    }

    func testAgeRollsOverOnTheBirthday() {
        // Birthday is 14 June: the day before is still 29, the day itself is 30.
        let c = T.calendar
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 0, calendar: c), 29)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 14), months: 0, calendar: c), 30)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 1, calendar: c), 30)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 12, calendar: c), 30)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 13, calendar: c), 31)
    }

    func testMonthEndClampDoesNotChangeTheAge() {
        // 31 Jan + 1 month clamps to 28 Feb (as java.time does); age is 29 either way.
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 1, 31), months: 1, calendar: T.calendar), 29)
    }

    func testMilestoneListMatchesTheDashboard() {
        XCTAssertEqual(ProjectionConfig.milestones, [100_000, 250_000, 500_000, 750_000, 1_000_000])
    }
}
