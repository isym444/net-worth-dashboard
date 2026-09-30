package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test fun symbols() {
        assertEquals("£", Money.symbol("GBP"))
        assertEquals("£", Money.symbol("gbp"))
        assertEquals("€", Money.symbol("EUR"))
        assertEquals("$", Money.symbol("USD"))
        assertEquals("¥", Money.symbol("JPY"))
        assertEquals("MX$", Money.symbol("MXN"))
    }

    @Test fun unknownCurrenciesUseTheirCodeAndBlankUsesNothing() {
        assertEquals("CHF ", Money.symbol("CHF"))
        assertEquals("", Money.symbol(""))
        assertEquals("", Money.symbol("  "))
    }

    @Test fun fullFormatsWholeUnitsWithGrouping() {
        assertEquals("£184,320", Money.full(184_320.25, "£"))
        assertEquals("£0", Money.full(0.0, "£"))
        assertEquals("$1,234,567", Money.full(1_234_567.0, "$"))
        assertEquals("CHF 1,234", Money.full(1_234.0, "CHF "))
    }

    @Test fun fullHandlesNegativesAndNeverPrintsMinusZero() {
        assertEquals("-£1,235", Money.full(-1_234.6, "£"))
        assertEquals("-£1,500", Money.full(-1_500.0, "£"))
        assertEquals("£0", Money.full(-0.2, "£"))
    }

    @Test fun compactAbbreviatesThousandsAndMillions() {
        assertEquals("£100k", Money.compact(100_000.0, "£"))
        assertEquals("£250k", Money.compact(250_000.0, "£"))
        assertEquals("£845k", Money.compact(845_300.0, "£"))
        assertEquals("£1m", Money.compact(1_000_000.0, "£"))
        assertEquals("£1.7m", Money.compact(1_700_000.0, "£"))
        assertEquals("£1.23m", Money.compact(1_234_567.0, "£"))
        assertEquals("£10m", Money.compact(10_000_000.0, "£"))
    }

    @Test fun compactSmallAndNegativeValues() {
        assertEquals("£999", Money.compact(999.0, "£"))
        assertEquals("£0", Money.compact(0.0, "£"))
        assertEquals("-£250k", Money.compact(-250_000.0, "£"))
        assertEquals("£0", Money.compact(-0.2, "£"))
    }
}
