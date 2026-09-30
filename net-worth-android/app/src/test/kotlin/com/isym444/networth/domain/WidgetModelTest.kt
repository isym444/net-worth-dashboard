package com.isym444.networth.domain

import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.CacheSnapshot
import com.isym444.networth.data.HistoryResponse
import com.isym444.networth.data.Snapshot
import com.isym444.networth.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class WidgetModelTest {
    // "Today" is 2026-09-30 in UTC, matching the values in ProjectionTest.
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val nowMs = now.toEpochMilli()
    private val hour = 60L * 60 * 1000

    private val configured = AppSettings("https://nwapi.isym.uk/", "key", "")
    private val ok = SyncStatus(lastSuccessAt = nowMs, lastError = null, keyRejected = false)
    private val rejected = SyncStatus(lastSuccessAt = nowMs - hour, lastError = "Access key rejected", keyRejected = true)

    private fun history(
        vararg totals: Double,
        currency: String = "GBP",
        monthly: Double? = 1_000.0,
        oneYear: Double? = 210_000.0,
        at40: Double? = 900_000.0, at50: Double? = 1_500_000.0, at60: Double? = 2_500_000.0, at65: Double? = 3_500_000.0,
        wc40: Double? = 1_000_000.0, wc50: Double? = 1_700_000.0, wc60: Double? = 2_900_000.0, wc65: Double? = 4_100_000.0,
    ) = HistoryResponse(
        snapshots = totals.mapIndexed { i, t -> Snapshot(id = i.toLong(), timestamp = "2026-09-2${i}T07:00:00Z", currency = currency, grandTotal = t) },
        expectedNetWorthAt40 = at40, expectedNetWorthAt50 = at50, expectedNetWorthAt60 = at60, expectedNetWorthAt65 = at65,
        expectedNetWorthWithContributionsAt40 = wc40, expectedNetWorthWithContributionsAt50 = wc50,
        expectedNetWorthWithContributionsAt60 = wc60, expectedNetWorthWithContributionsAt65 = wc65,
        estimatedNetWorth1y = oneYear, monthlyContribution = monthly,
    )

    private fun build(
        h: HistoryResponse?,
        config: WidgetConfig = WidgetConfig(),
        settings: AppSettings = configured,
        status: SyncStatus = ok,
        fetchedAt: Long = nowMs,
    ): WidgetState = WidgetModel.build(CacheSnapshot(fetchedAt = fetchedAt, history = h), config, settings, status, now, ZoneOffset.UTC)

    private fun ready(
        h: HistoryResponse?,
        config: WidgetConfig = WidgetConfig(),
        status: SyncStatus = ok,
        fetchedAt: Long = nowMs,
    ) = build(h, config, configured, status, fetchedAt) as WidgetState.Ready

    // ---- empty and unconfigured states -----------------------------------------------------------------

    @Test fun notConfiguredWhenThereIsNoDataAndNoSettings() =
        assertEquals(WidgetState.NotConfigured, build(null, settings = AppSettings()))

    @Test fun waitingForFirstSyncWhenConfiguredButNothingCached() =
        assertEquals(WidgetState.Empty("Waiting for first sync"), build(null))

    @Test fun aRejectedKeyBeforeAnySyncSaysSo() {
        val s = build(null, status = rejected) as WidgetState.Empty
        assertTrue(s.message, s.message.contains("Key rejected"))
    }

    @Test fun aBrandNewDatabaseShowsNoSnapshotsYet() =
        assertEquals(WidgetState.Empty("No snapshots yet"), build(history()))

    // ---- headline and change ---------------------------------------------------------------------------

    @Test fun headlineUsesTheLatestSnapshotAndItsCurrency() {
        assertEquals("£184,320", ready(history(183_000.0, 184_320.25)).headline)
        assertEquals("€1,000", ready(history(1_000.0, currency = "EUR")).headline)
    }

    @Test fun changeUpAgainstThePreviousSnapshot() {
        val s = ready(history(184_320.0, 185_554.4))
        assertEquals("+£1,234 (+0.7%)", s.change)
        assertEquals(true, s.changeUp)
    }

    @Test fun changeDown() {
        val s = ready(history(100_000.0, 99_500.0))
        assertEquals("-£500 (-0.5%)", s.change)
        assertEquals(false, s.changeUp)
    }

    @Test fun noChange() {
        val s = ready(history(100_000.0, 100_000.0))
        assertEquals("No change", s.change)
        assertNull(s.changeUp)
    }

    @Test fun aSingleSnapshotHasNoChangeLine() {
        val s = ready(history(50_000.0))
        assertNull(s.change)
        assertNull(s.changeUp)
    }

    @Test fun changeFromAZeroBaselineOmitsThePercentage() =
        assertEquals("+£500", ready(history(0.0, 500.0)).change)

    // ---- milestones ------------------------------------------------------------------------------------

    @Test fun milestonesWithContributionsMatchTheDashboard() {
        val m = ready(history(183_000.0, 184_320.0)).milestones
        assertEquals(listOf("£100k", "£250k", "£500k", "£750k", "£1m"), m.map { it.label })
        assertEquals("Reached (age 30)", m[0].value)
        assertTrue(m[0].reached)
        assertEquals("2y 6m (age 32)", m[1].value)
        assertEquals(false, m[1].reached)
    }

    @Test fun milestonesWithoutContributionsMatchTheDashboard() {
        val m = ready(history(184_320.0), WidgetConfig(withContributions = false)).milestones
        assertEquals("4y 7m (age 34)", m[1].value)
        assertEquals("25y 0m (age 55)", m[4].value)
    }

    @Test fun aMissingMonthlyContributionActsLikeZero() {
        val m = ready(history(184_320.0, monthly = null)).milestones
        assertEquals("4y 7m (age 34)", m[1].value)
    }

    @Test fun nextMilestonesSkipsTheOnesAlreadyReached() {
        val s = ready(history(184_320.0))
        assertEquals(listOf("£250k", "£500k", "£750k"), s.nextMilestones(3).map { it.label })
    }

    @Test fun nextMilestonesIsEmptyWhenEverythingIsReached() =
        assertTrue(ready(history(2_000_000.0)).nextMilestones(3).isEmpty())

    // ---- projections and the one-year estimate ----------------------------------------------------------

    @Test fun projectionsFollowTheContributionSetting() {
        val h = history(184_320.0)
        assertEquals(
            listOf("Age 40" to "£1m", "Age 50" to "£1.7m", "Age 60" to "£2.9m", "Age 65" to "£4.1m"),
            ready(h).projections.map { it.label to it.value },
        )
        assertEquals(
            listOf("Age 40" to "£900k", "Age 50" to "£1.5m", "Age 60" to "£2.5m", "Age 65" to "£3.5m"),
            ready(h, WidgetConfig(withContributions = false)).projections.map { it.label to it.value },
        )
    }

    @Test fun nullProjectionsAreOmittedAndNeverCrash() {
        val s = ready(history(184_320.0, oneYear = null, wc40 = null, wc50 = null, wc60 = null, wc65 = null))
        assertTrue(s.projections.isEmpty())
        assertNull(s.oneYear)
        assertEquals(5, s.milestones.size)          // milestones still work without projections
    }

    @Test fun oneYearEstimateIsCompact() = assertEquals("£210k", ready(history(184_320.0)).oneYear)

    // ---- zero, negative, odd currencies ----------------------------------------------------------------

    @Test fun negativeNetWorthFormatsWithAMinusAndNeverReachesWithoutContributions() {
        val s = ready(history(-1_500.0), WidgetConfig(withContributions = false))
        assertEquals("-£1,500", s.headline)
        assertTrue(s.milestones.all { it.value == "100y+" && !it.reached })
    }

    @Test fun zeroNetWorthDoesNotCrash() {
        val s = ready(history(0.0), WidgetConfig(withContributions = false))
        assertEquals("£0", s.headline)
        assertEquals("100y+", s.milestones[0].value)
    }

    @Test fun anUnknownCurrencyUsesItsCode() = assertEquals("CHF 1,000", ready(history(1_000.0, currency = "CHF")).headline)

    @Test fun aBlankCurrencyHasNoSymbol() = assertEquals("1,000", ready(history(1_000.0, currency = "")).headline)

    // ---- freshness and hints ---------------------------------------------------------------------------

    @Test fun updatedTextIsRelative() {
        assertEquals("Updated just now", ready(history(1.0), fetchedAt = nowMs - 20_000).updated)
        assertEquals("Updated 5m ago", ready(history(1.0), fetchedAt = nowMs - 5 * 60_000).updated)
        assertEquals("Updated 3h ago", ready(history(1.0), fetchedAt = nowMs - 3 * hour).updated)
        assertEquals("Updated 2d ago", ready(history(1.0), fetchedAt = nowMs - 49 * hour).updated)
    }

    @Test fun aClockSkewedFutureFetchTimeReadsAsJustNow() =
        assertEquals("Updated just now", ready(history(1.0), fetchedAt = nowMs + hour).updated)

    @Test fun noHintWhenFresh() = assertNull(ready(history(1.0), fetchedAt = nowMs - 5 * hour).hint)

    @Test fun staleDataGetsAnOutOfDateHintButStaysOnScreen() {
        val s = ready(history(184_320.0), fetchedAt = nowMs - 7 * hour)
        assertEquals("Out of date — can't reach the server", s.hint)
        assertEquals("£184,320", s.headline)
    }

    @Test fun aRejectedKeyKeepsTheLastDataAndShowsAHint() {
        val s = ready(history(184_320.0), status = rejected)
        assertEquals("Key rejected — update it in the app", s.hint)
        assertEquals("£184,320", s.headline)
    }
}
