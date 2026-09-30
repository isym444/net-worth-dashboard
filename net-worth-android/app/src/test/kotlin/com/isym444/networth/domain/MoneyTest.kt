package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test fun units() {
        assertEquals("ポンド", Money.unit("GBP"))
        assertEquals("ポンド", Money.unit("gbp"))
        assertEquals("ユーロ", Money.unit("EUR"))
        assertEquals("ドル", Money.unit("USD"))
        assertEquals("円", Money.unit("JPY"))
        assertEquals("ペソ", Money.unit("MXN"))
    }

    @Test fun unknownCurrenciesUseTheirCodeAndBlankUsesNothing() {
        assertEquals(" CHF", Money.unit("CHF"))
        assertEquals("", Money.unit(""))
        assertEquals("", Money.unit("  "))
    }

    @Test fun fullFormatsWholeUnitsWithGrouping() {
        assertEquals("184,320ポンド", Money.full(184_320.25, "ポンド"))
        assertEquals("0ポンド", Money.full(0.0, "ポンド"))
        assertEquals("1,234,567ドル", Money.full(1_234_567.0, "ドル"))
        assertEquals("1,234 CHF", Money.full(1_234.0, " CHF"))
    }

    @Test fun fullHandlesNegativesAndNeverPrintsMinusZero() {
        assertEquals("-1,235ポンド", Money.full(-1_234.6, "ポンド"))
        assertEquals("-1,500ポンド", Money.full(-1_500.0, "ポンド"))
        assertEquals("0ポンド", Money.full(-0.2, "ポンド"))
        assertEquals("-1,235", Money.number(-1_234.6))
    }

    @Test fun compactUsesJapaneseManAndOkuUnits() {
        assertEquals("10万ポンド", Money.compact(100_000.0, "ポンド"))
        assertEquals("25万ポンド", Money.compact(250_000.0, "ポンド"))
        assertEquals("84.5万ポンド", Money.compact(845_300.0, "ポンド"))
        assertEquals("6.5万ポンド", Money.compact(65_000.0, "ポンド"))
        assertEquals("100万ポンド", Money.compact(1_000_000.0, "ポンド"))
        assertEquals("170万ポンド", Money.compact(1_700_000.0, "ポンド"))
        assertEquals("123.5万ポンド", Money.compact(1_234_567.0, "ポンド"))
        assertEquals("1000万ポンド", Money.compact(10_000_000.0, "ポンド"))
        assertEquals("1億ポンド", Money.compact(100_000_000.0, "ポンド"))
        assertEquals("1.5億ポンド", Money.compact(150_000_000.0, "ポンド"))
    }

    @Test fun compactSmallAndNegativeValues() {
        assertEquals("999ポンド", Money.compact(999.0, "ポンド"))
        assertEquals("5,000ポンド", Money.compact(5_000.0, "ポンド"))
        assertEquals("0ポンド", Money.compact(0.0, "ポンド"))
        assertEquals("-25万ポンド", Money.compact(-250_000.0, "ポンド"))
        assertEquals("0ポンド", Money.compact(-0.2, "ポンド"))
    }

    @Test fun compactRoundsUpAcrossUnitBoundaries() {
        assertEquals("10万ポンド", Money.compact(99_999.0, "ポンド"))
        assertEquals("1億ポンド", Money.compact(99_999_999.0, "ポンド"))
    }
}
