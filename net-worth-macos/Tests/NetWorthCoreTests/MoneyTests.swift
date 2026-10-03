import XCTest
@testable import NetWorthCore

final class MoneyTests: XCTestCase {
    func testUnits() {
        XCTAssertEqual(Money.unit("GBP"), "ポンド")
        XCTAssertEqual(Money.unit("gbp"), "ポンド")
        XCTAssertEqual(Money.unit("EUR"), "ユーロ")
        XCTAssertEqual(Money.unit("USD"), "ドル")
        XCTAssertEqual(Money.unit("JPY"), "円")
        XCTAssertEqual(Money.unit("MXN"), "ペソ")
    }

    func testUnknownCurrenciesUseTheirCodeAndBlankUsesNothing() {
        XCTAssertEqual(Money.unit("CHF"), " CHF")
        XCTAssertEqual(Money.unit(""), "")
        XCTAssertEqual(Money.unit("  "), "")
    }

    func testFullFormatsWholeUnitsWithGrouping() {
        XCTAssertEqual(Money.full(184_320.25, unit: "ポンド"), "184,320ポンド")
        XCTAssertEqual(Money.full(0, unit: "ポンド"), "0ポンド")
        XCTAssertEqual(Money.full(1_234_567, unit: "ドル"), "1,234,567ドル")
        XCTAssertEqual(Money.full(1_234, unit: " CHF"), "1,234 CHF")
    }

    func testFullHandlesNegativesAndNeverPrintsMinusZero() {
        XCTAssertEqual(Money.full(-1_234.6, unit: "ポンド"), "-1,235ポンド")
        XCTAssertEqual(Money.full(-1_500, unit: "ポンド"), "-1,500ポンド")
        XCTAssertEqual(Money.full(-0.2, unit: "ポンド"), "0ポンド")
        XCTAssertEqual(Money.number(-1_234.6), "-1,235")
    }

    func testCompactUsesJapaneseManAndOkuUnits() {
        XCTAssertEqual(Money.compact(100_000, unit: "ポンド"), "10万ポンド")
        XCTAssertEqual(Money.compact(250_000, unit: "ポンド"), "25万ポンド")
        XCTAssertEqual(Money.compact(845_300, unit: "ポンド"), "84.5万ポンド")
        XCTAssertEqual(Money.compact(65_000, unit: "ポンド"), "6.5万ポンド")
        XCTAssertEqual(Money.compact(1_000_000, unit: "ポンド"), "100万ポンド")
        XCTAssertEqual(Money.compact(1_700_000, unit: "ポンド"), "170万ポンド")
        XCTAssertEqual(Money.compact(1_234_567, unit: "ポンド"), "123.5万ポンド")
        XCTAssertEqual(Money.compact(10_000_000, unit: "ポンド"), "1000万ポンド")
        XCTAssertEqual(Money.compact(100_000_000, unit: "ポンド"), "1億ポンド")
        XCTAssertEqual(Money.compact(150_000_000, unit: "ポンド"), "1.5億ポンド")
    }

    func testCompactSmallAndNegativeValues() {
        XCTAssertEqual(Money.compact(999, unit: "ポンド"), "999ポンド")
        XCTAssertEqual(Money.compact(5_000, unit: "ポンド"), "5,000ポンド")
        XCTAssertEqual(Money.compact(0, unit: "ポンド"), "0ポンド")
        XCTAssertEqual(Money.compact(-250_000, unit: "ポンド"), "-25万ポンド")
        XCTAssertEqual(Money.compact(-0.2, unit: "ポンド"), "0ポンド")
    }

    func testCompactRoundsUpAcrossUnitBoundaries() {
        XCTAssertEqual(Money.compact(99_999, unit: "ポンド"), "10万ポンド")
        XCTAssertEqual(Money.compact(99_999_999, unit: "ポンド"), "1億ポンド")
    }
}
