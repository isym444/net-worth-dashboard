import XCTest
@testable import NetWorthCore

final class GoalProgressTests: XCTestCase {
    func testMatchesTheWorkedExample() {
        let g = GoalProgress.compute(p: 48_000, t: 100_000, c: 1_000, annualReturn: 0.07)
        XCTAssertEqual(g.monthsRemaining!, 36.9, accuracy: 0.05)
        XCTAssertEqual(g.growthShare, 0.591, accuracy: 0.001)      // money progress would be 48.0%
        XCTAssertEqual(g.timeShare!, 0.536, accuracy: 0.001)
    }

    func testAlreadyReachedIsEverythingAt100Percent() {
        XCTAssertEqual(GoalProgress.compute(p: 120_000, t: 100_000, c: 1_000, annualReturn: 0.07),
                       GoalProgress(monthsRemaining: 0, growthShare: 1, timeShare: 1))
    }

    func testWithoutContributionsItIsAllYourExistingMoneyAndTimeProgressIsUndefined() {
        let g = GoalProgress.compute(p: 48_000, t: 100_000, c: 0, annualReturn: 0.07)
        XCTAssertEqual(g.growthShare, 1, accuracy: 1e-9)
        XCTAssertNil(g.timeShare)
        XCTAssertEqual(g.monthsRemaining!, log(100_000.0 / 48_000.0) / log(1.07) * 12, accuracy: 0.01)
    }

    func testWithoutReturnItIsMoneyProgressAndStraightLineMonths() {
        let g = GoalProgress.compute(p: 40_000, t: 100_000, c: 1_000, annualReturn: 0)
        XCTAssertEqual(g.monthsRemaining!, 60, accuracy: 1e-9)
        XCTAssertEqual(g.growthShare, 0.4, accuracy: 1e-9)
        XCTAssertEqual(g.timeShare!, 0.4, accuracy: 1e-9)
    }

    func testNoReturnAndNoContributionsCanNeverReachIt() {
        let g = GoalProgress.compute(p: 40_000, t: 100_000, c: 0, annualReturn: 0)
        XCTAssertNil(g.monthsRemaining)
        XCTAssertNil(g.timeShare)
    }

    func testStartingFromNothingIsZeroTimeProgressAndZeroGrowthShare() {
        let g = GoalProgress.compute(p: 0, t: 100_000, c: 1_000, annualReturn: 0.07)
        XCTAssertEqual(g.timeShare!, 0, accuracy: 1e-9)
        XCTAssertEqual(g.growthShare, 0, accuracy: 1e-9)
        XCTAssertNotNil(g.monthsRemaining)
    }

    func testDebtBeyondWhatContributionsCanCatchUpIsUnreachable() {
        let g = GoalProgress.compute(p: -500_000, t: 100_000, c: 1_000, annualReturn: 0.07)
        XCTAssertNil(g.monthsRemaining)
        XCTAssertEqual(g.growthShare, 0)
    }

    func testBothMeasuresGrowAsTheSavingsGrow() {
        let runs = [5_000.0, 20_000, 50_000, 90_000].map { GoalProgress.compute(p: $0, t: 100_000, c: 1_000, annualReturn: 0.07) }
        XCTAssertEqual(runs.map { $0.growthShare }, runs.map { $0.growthShare }.sorted())
        XCTAssertEqual(runs.map { $0.timeShare! }, runs.map { $0.timeShare! }.sorted())
    }
}
