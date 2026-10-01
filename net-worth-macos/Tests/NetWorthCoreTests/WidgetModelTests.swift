import XCTest
@testable import NetWorthCore

final class WidgetModelTests: XCTestCase {
    // "Now" is 2026-09-30 12:00 UTC, matching ProjectionTests.
    private let now = ISO8601DateFormatter().date(from: "2026-09-30T12:00:00Z")!
    private var nowMs: Int64 { Int64(now.timeIntervalSince1970 * 1000) }
    private let hour: Int64 = 60 * 60 * 1000

    private let configured = AppSettings(baseUrl: "https://nwapi.isym.uk/", apiKey: "key", webUrl: "")
    private var ok: SyncStatus { SyncStatus(lastSuccessAt: nowMs, lastError: nil, keyRejected: false) }
    private var rejected: SyncStatus { SyncStatus(lastSuccessAt: nowMs - hour, lastError: "Access key rejected", keyRejected: true) }

    private func history(
        _ totals: Double..., currency: String = "GBP", monthly: Double? = 1_000, oneYear: Double? = 210_000,
        at40: Double? = 900_000, at50: Double? = 1_500_000, at60: Double? = 2_500_000, at65: Double? = 3_500_000,
        wc40: Double? = 1_000_000, wc50: Double? = 1_700_000, wc60: Double? = 2_900_000, wc65: Double? = 4_100_000
    ) -> HistoryResponse {
        HistoryResponse(
            snapshots: totals.enumerated().map { i, t in
                Snapshot(id: Int64(i), timestamp: "2026-09-2\(i)T07:00:00Z", currency: currency, grandTotal: t)
            },
            expectedNetWorthAt40: at40, expectedNetWorthAt50: at50, expectedNetWorthAt60: at60, expectedNetWorthAt65: at65,
            expectedNetWorthWithContributionsAt40: wc40, expectedNetWorthWithContributionsAt50: wc50,
            expectedNetWorthWithContributionsAt60: wc60, expectedNetWorthWithContributionsAt65: wc65,
            estimatedNetWorth1y: oneYear, monthlyContribution: monthly)
    }

    /// Snapshots at "days ago" offsets from `now`, oldest first, with the same projections as `history`.
    private func dated(_ points: (Int, Double)...) -> HistoryResponse {
        HistoryResponse(
            snapshots: points.enumerated().map { i, p in
                Snapshot(id: Int64(i), timestamp: T.iso(now.addingTimeInterval(-Double(p.0) * 86_400)), currency: "GBP", grandTotal: p.1)
            },
            estimatedNetWorth1y: 210_000, monthlyContribution: 1_000)
    }

    private func build(
        _ h: HistoryResponse?, config: WidgetConfig = WidgetConfig(), settings: AppSettings? = nil,
        status: SyncStatus? = nil, fetchedAt: Int64? = nil
    ) -> WidgetState {
        WidgetModel.build(
            cache: CacheSnapshot(fetchedAt: fetchedAt ?? nowMs, history: h), config: config,
            settings: settings ?? configured, status: status ?? ok, now: now, calendar: T.calendar)
    }

    private func ready(_ h: HistoryResponse?, config: WidgetConfig = WidgetConfig(), status: SyncStatus? = nil, fetchedAt: Int64? = nil)
        -> WidgetState.Ready {
        guard case .ready(let r) = build(h, config: config, status: status, fetchedAt: fetchedAt) else {
            XCTFail("expected ready"); fatalError()
        }
        return r
    }

    // MARK: daily spend

    private func withBank(_ chase: Double?, _ amex: Double?) -> HistoryResponse {
        HistoryResponse(snapshots: [Snapshot(
            id: 1, timestamp: "2026-09-29T07:00:00Z", currency: "GBP", grandTotal: 50_000, chaseTotal: chase, amexDebtTotal: amex)])
    }

    func testMaxDailySpendWithAndWithoutTheInvestBuffer() {
        // 2026-09-30 -> payday Fri 2026-10-23 = 23 days. (2,100 - 450) / 23 = 71.74; minus 400 buffer: 1,250 / 23 = 54.35. Subscriptions are not deducted.
        let r = ready(withBank(2_100, 450))
        XCTAssertEqual(r.daysToPayday, 23)
        XCTAssertEqual(r.maxDailySpend?.text, "1日の上限 72ポンド")
        XCTAssertEqual(r.maxDailySpendBuffer?.text, "400ポンド確保で 54ポンド")
    }

    func testMaxDailySpendCanGoNegative() {
        XCTAssertEqual(ready(withBank(500, 450)).maxDailySpendBuffer?.text, "400ポンド確保で -15ポンド")
    }

    func testNoDailySpendWhenTheSnapshotLacksChaseOrAmex() {
        XCTAssertNil(ready(withBank(nil, nil)).maxDailySpend)
        XCTAssertNil(ready(withBank(2_100, nil)).maxDailySpendBuffer)
    }

    func testSnapshotDecodesChaseAndAmexAndToleratesTheirAbsence() throws {
        let full = try JSONDecoder().decode(Snapshot.self, from: Data(#"{"grandTotal":1,"chaseTotal":2.5,"amexDebtTotal":3}"#.utf8))
        XCTAssertEqual(full.chaseTotal, 2.5)
        XCTAssertEqual(full.amexDebtTotal, 3)
        let old = try JSONDecoder().decode(Snapshot.self, from: Data(#"{"grandTotal":1,"chaseTotal":null}"#.utf8))
        XCTAssertNil(old.chaseTotal)
        XCTAssertNil(old.amexDebtTotal)
    }

    // MARK: timestamps

    func testParsesTimestampsWithAndWithoutFractionalSeconds() {
        XCTAssertNotNil(WidgetModel.parseInstant("2026-08-01T07:00:03.533248Z"))
        XCTAssertNotNil(WidgetModel.parseInstant("2020-07-01T12:00:00Z"))
        XCTAssertNil(WidgetModel.parseInstant("nope"))
    }

    // MARK: empty and unconfigured states

    func testNotConfiguredWhenThereIsNoDataAndNoSettings() {
        XCTAssertEqual(build(nil, settings: AppSettings()), .notConfigured)
    }

    func testWaitingForFirstSyncWhenConfiguredButNothingCached() {
        XCTAssertEqual(build(nil), .empty("初回の同期を待っています"))
    }

    func testARejectedKeyBeforeAnySyncSaysSo() {
        guard case .empty(let m) = build(nil, status: rejected) else { return XCTFail() }
        XCTAssertTrue(m.contains("キーが拒否されました"), m)
    }

    func testABrandNewDatabaseShowsNoDataYet() {
        XCTAssertEqual(build(history()), .empty("データがまだありません"))
    }

    // MARK: headline and change

    func testHeadlineUsesTheLatestSnapshotAndItsCurrency() {
        XCTAssertEqual(ready(history(183_000, 184_320.25)).headline, "184,320ポンド")
        XCTAssertEqual(ready(history(1_000, currency: "EUR")).headline, "1,000ユーロ")
    }

    func testHeadlineIsSplitIntoAmountAndUnit() {
        let s = ready(history(184_320))
        XCTAssertEqual(s.headlineAmount, "184,320")
        XCTAssertEqual(s.headlineUnit, "ポンド")
    }

    func testChangeUpAgainstThePreviousSnapshot() {
        let s = ready(history(184_320, 185_554.4))
        XCTAssertEqual(s.change, "+1,234ポンド (+0.7%)")
        XCTAssertEqual(s.changeUp, true)
    }

    func testChangeDown() {
        let s = ready(history(100_000, 99_500))
        XCTAssertEqual(s.change, "-500ポンド (-0.5%)")
        XCTAssertEqual(s.changeUp, false)
    }

    func testNoChange() {
        let s = ready(history(100_000, 100_000))
        XCTAssertEqual(s.change, "変化なし")
        XCTAssertNil(s.changeUp)
    }

    func testASingleSnapshotHasNoChangeLine() {
        let s = ready(history(50_000))
        XCTAssertNil(s.change)
        XCTAssertNil(s.changeUp)
    }

    func testChangeFromAZeroBaselineOmitsThePercentage() {
        XCTAssertEqual(ready(history(0, 500)).change, "+500ポンド")
    }

    // MARK: milestones

    func testMilestonesWithContributionsMatchTheDashboard() {
        let m = ready(history(183_000, 184_320)).milestones
        XCTAssertEqual(m.map { $0.label }, ["10万ポンド", "25万ポンド", "50万ポンド", "75万ポンド", "100万ポンド"])
        XCTAssertEqual(m[0].value, "達成 (30歳)")
        XCTAssertTrue(m[0].reached)
        XCTAssertEqual(m[1].value, "2年6か月 (32歳)")
        XCTAssertFalse(m[1].reached)
    }

    func testMilestonesWithoutContributionsMatchTheDashboard() {
        let m = ready(history(184_320), config: WidgetConfig(withContributions: false)).milestones
        XCTAssertEqual(m[1].value, "4年7か月 (34歳)")
        XCTAssertEqual(m[4].value, "25年0か月 (55歳)")
    }

    func testAMissingMonthlyContributionActsLikeZero() {
        XCTAssertEqual(ready(history(184_320, monthly: nil)).milestones[1].value, "4年7か月 (34歳)")
    }

    func testNextMilestonesSkipsTheOnesAlreadyReached() {
        XCTAssertEqual(ready(history(184_320)).nextMilestones(3).map { $0.label }, ["25万ポンド", "50万ポンド", "75万ポンド"])
    }

    func testNextMilestonesIsEmptyWhenEverythingIsReached() {
        XCTAssertTrue(ready(history(2_000_000)).nextMilestones(3).isEmpty)
    }

    // MARK: projections and the one-year estimate

    func testProjectionsFollowTheContributionSetting() {
        let h = history(184_320)
        XCTAssertEqual(ready(h).projections.map { "\($0.label)=\($0.value)" },
                       ["40歳=100万ポンド", "50歳=170万ポンド", "60歳=290万ポンド", "65歳=410万ポンド"])
        XCTAssertEqual(ready(h, config: WidgetConfig(withContributions: false)).projections.map { "\($0.label)=\($0.value)" },
                       ["40歳=90万ポンド", "50歳=150万ポンド", "60歳=250万ポンド", "65歳=350万ポンド"])
    }

    func testNilProjectionsAreOmittedAndNeverCrash() {
        let s = ready(history(184_320, oneYear: nil, wc40: nil, wc50: nil, wc60: nil, wc65: nil))
        XCTAssertTrue(s.projections.isEmpty)
        XCTAssertNil(s.oneYear)
        XCTAssertEqual(s.milestones.count, 5)
    }

    func testOneYearEstimateIsCompact() { XCTAssertEqual(ready(history(184_320)).oneYear, "21万ポンド") }

    // MARK: zero, negative, odd currencies

    func testNegativeNetWorthFormatsWithAMinusAndNeverReachesWithoutContributions() {
        let s = ready(history(-1_500), config: WidgetConfig(withContributions: false))
        XCTAssertEqual(s.headline, "-1,500ポンド")
        XCTAssertTrue(s.milestones.allSatisfy { $0.value == "100年以上" && !$0.reached })
    }

    func testZeroNetWorthDoesNotCrash() {
        let s = ready(history(0), config: WidgetConfig(withContributions: false))
        XCTAssertEqual(s.headline, "0ポンド")
        XCTAssertEqual(s.milestones[0].value, "100年以上")
    }

    func testAnUnknownCurrencyUsesItsCode() { XCTAssertEqual(ready(history(1_000, currency: "CHF")).headline, "1,000 CHF") }
    func testABlankCurrencyHasNoUnit() { XCTAssertEqual(ready(history(1_000, currency: "")).headline, "1,000") }

    // MARK: freshness and hints

    func testUpdatedTextIsRelative() {
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 20_000).updated, "たった今更新")
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 5 * 60_000).updated, "5分前に更新")
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 3 * hour).updated, "3時間前に更新")
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 49 * hour).updated, "2日前に更新")
    }

    func testAClockSkewedFutureFetchTimeReadsAsJustNow() {
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs + hour).updated, "たった今更新")
    }

    func testNoHintWhenFresh() { XCTAssertNil(ready(history(1), fetchedAt: nowMs - 5 * hour).hint) }

    func testStaleDataGetsAnOutOfDateHintButStaysOnScreen() {
        let s = ready(history(184_320), fetchedAt: nowMs - 7 * hour)
        XCTAssertEqual(s.hint, "古いデータ — サーバーに接続できません")
        XCTAssertEqual(s.headline, "184,320ポンド")
    }

    func testARejectedKeyKeepsTheLastDataAndShowsAHint() {
        let s = ready(history(184_320), status: rejected)
        XCTAssertEqual(s.hint, "キーが拒否されました — アプリで更新してください")
        XCTAssertEqual(s.headline, "184,320ポンド")
    }

    // MARK: assumptions line

    func testAssumptionMentionsTheReturnAndTheMonthlyContribution() {
        XCTAssertEqual(ready(history(184_320)).assumption, "年7%運用・毎月1,000ポンド積立")
        XCTAssertEqual(ready(history(184_320), config: WidgetConfig(withContributions: false)).assumption, "年7%運用・積立なし")
        XCTAssertEqual(ready(history(184_320, monthly: nil)).assumption, "年7%運用・積立なし")
    }

    // MARK: next goal (two progress bars)

    func testNextGoalShowsBothProgressBars() {
        let g = ready(history(48_000)).nextGoal!                 // 7% a year, £1,000 a month
        XCTAssertEqual(g.label, "10万ポンド")
        XCTAssertEqual(g.growth.percent, "59.1%")
        XCTAssertEqual(g.growth.fraction, 0.591, accuracy: 0.001)
        XCTAssertEqual(g.time!.percent, "53.6%")
        XCTAssertEqual(g.remaining, "あと3年1か月")                // 37 months, the same figure as the milestone rows
    }

    func testNextGoalProgressDoesNotDependOnHowLongYouHaveBeenTracking() {
        XCTAssertEqual(ready(dated((3, 40_000), (0, 48_000))).nextGoal, ready(dated((2_280, 1_900), (0, 48_000))).nextGoal)
    }

    func testWithoutContributionsTimeProgressIsNotAvailable() {
        let g = ready(history(48_000), config: WidgetConfig(withContributions: false)).nextGoal!
        XCTAssertEqual(g.growth.percent, "100.0%")
        XCTAssertNil(g.time)
    }

    func testNextGoalWithNoWayToReachItSaysSo() {
        let g = ready(history(0), config: WidgetConfig(withContributions: false)).nextGoal!
        XCTAssertEqual(g.growth.percent, "0.0%")
        XCTAssertEqual(g.remaining, "あと100年以上")
    }

    func testNextGoalSkipsReachedMilestonesAndIsNilWhenAllAreReached() {
        XCTAssertEqual(ready(history(184_320)).nextGoal!.label, "25万ポンド")
        XCTAssertNil(ready(history(2_000_000)).nextGoal)
    }

    // MARK: period changes, peak, trend

    func testPeriodChangesCompareAgainstTheLatestSnapshotAtOrBeforeEachCutoff() {
        let s = ready(dated((40, 40_000), (30, 44_000), (8, 46_000), (0, 48_000)))
        XCTAssertEqual(s.periodChanges.map { $0.label }, ["7日間", "30日間", "全期間"])
        XCTAssertEqual(s.periodChanges[0].text, "+2,000ポンド (+4.3%)")      // 8 days ago: 46,000
        XCTAssertEqual(s.periodChanges[1].text, "+4,000ポンド (+9.1%)")      // 30 days ago: 44,000
        XCTAssertEqual(s.periodChanges[2].text, "+8,000ポンド (+20.0%)")     // first snapshot: 40,000
        XCTAssertTrue(s.periodChanges.allSatisfy { $0.up == true })
    }

    func testPeriodsLongerThanTheHistoryAreOmitted() {
        let s = ready(dated((3, 100), (0, 90)))
        XCTAssertEqual(s.periodChanges.map { $0.label }, ["全期間"])
        XCTAssertEqual(s.periodChanges[0].up, false)
    }

    func testASingleSnapshotHasNoPeriodChangesAndUnparseableTimestampsAreIgnored() {
        XCTAssertTrue(ready(dated((0, 100))).periodChanges.isEmpty)
        let bad = HistoryResponse(snapshots: [Snapshot(id: 1, timestamp: "nope", currency: "GBP", grandTotal: 1),
                                              Snapshot(id: 2, timestamp: "nah", currency: "GBP", grandTotal: 2)])
        let s = ready(bad)
        XCTAssertTrue(s.periodChanges.isEmpty)
        XCTAssertTrue(s.trend.isEmpty)
        XCTAssertEqual(s.trendSpan, "")
    }

    func testPeakIsTheHighestSnapshotEver() {
        XCTAssertEqual(ready(dated((9, 40_000), (5, 50_000), (0, 48_000))).peak, "最高値 50,000ポンド")
    }

    func testTrendKeepsTheLastTwoYearsOldestFirstWithItsSpan() {
        let s = ready(dated((900, 1), (400, 2), (5, 3), (0, 4)))
        XCTAssertEqual(s.trend.map { $0.total }, [2, 3, 4])               // the 900-day-old snapshot is outside the window
        XCTAssertTrue(zip(s.trend, s.trend.dropFirst()).allSatisfy { $0.date < $1.date })
        XCTAssertEqual(s.trendSpan, "13か月間")                            // 400 days ago to today
    }

    func testTrendIsThinnedEvenlyKeepingTheFirstAndLastPoint() {
        let s = ready(dated(contentsOf: (0...599).map { (599 - $0, Double($0)) }))
        XCTAssertEqual(s.trend.count, WidgetModel.trendMaxPoints)
        XCTAssertEqual(s.trend.first!.total, 0)
        XCTAssertEqual(s.trend.last!.total, 599)
    }

    func testTrendSpanIsWrittenInDaysMonthsOrYears() {
        XCTAssertEqual(ready(dated((12, 1), (0, 2))).trendSpan, "12日間")
        XCTAssertEqual(ready(dated((152, 1), (0, 2))).trendSpan, "5か月間")
        XCTAssertEqual(ready(dated((730, 1), (0, 2))).trendSpan, "2年間")
    }

    func testProjectionTableShowsBothScenarios() {
        let t = ready(history(184_320)).projectionTable
        XCTAssertEqual(t.map { $0.label }, ["40歳", "50歳", "60歳", "65歳"])
        XCTAssertEqual(t[0].withContributions, "100万ポンド")
        XCTAssertEqual(t[0].withoutContributions, "90万ポンド")
    }

    func testProjectionTableUsesADashForAMissingScenario() {
        let t = ready(history(184_320, wc40: nil)).projectionTable
        XCTAssertEqual(t[0].withContributions, "-")
        XCTAssertEqual(t[0].withoutContributions, "90万ポンド")
    }
}

private extension WidgetModelTests {
    func dated(contentsOf points: [(Int, Double)]) -> HistoryResponse {
        HistoryResponse(
            snapshots: points.enumerated().map { i, p in
                Snapshot(id: Int64(i), timestamp: T.iso(now.addingTimeInterval(-Double(p.0) * 86_400)), currency: "GBP", grandTotal: p.1)
            },
            estimatedNetWorth1y: 210_000, monthlyContribution: 1_000)
    }
}
