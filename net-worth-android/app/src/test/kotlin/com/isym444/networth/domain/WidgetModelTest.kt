package com.isym444.networth.domain

import com.isym444.networth.data.ApiJson
import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.CacheSnapshot
import com.isym444.networth.data.HistoryResponse
import com.isym444.networth.data.Snapshot
import com.isym444.networth.data.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals(WidgetState.Empty("初回の同期を待っています"), build(null))

    @Test fun aRejectedKeyBeforeAnySyncSaysSo() {
        val s = build(null, status = rejected) as WidgetState.Empty
        assertTrue(s.message, s.message.contains("キーが拒否されました"))
    }

    @Test fun aBrandNewDatabaseShowsNoSnapshotsYet() =
        assertEquals(WidgetState.Empty("データがまだありません"), build(history()))

    // ---- headline and change ---------------------------------------------------------------------------

    @Test fun headlineUsesTheLatestSnapshotAndItsCurrency() {
        assertEquals("184,320ポンド", ready(history(183_000.0, 184_320.25)).headline)
        assertEquals("1,000ユーロ", ready(history(1_000.0, currency = "EUR")).headline)
    }

    @Test fun changeUpAgainstThePreviousSnapshot() {
        val s = ready(history(184_320.0, 185_554.4))
        assertEquals("+1,234ポンド (+0.7%)", s.change)
        assertEquals(true, s.changeUp)
    }

    @Test fun changeDown() {
        val s = ready(history(100_000.0, 99_500.0))
        assertEquals("-500ポンド (-0.5%)", s.change)
        assertEquals(false, s.changeUp)
    }

    @Test fun noChange() {
        val s = ready(history(100_000.0, 100_000.0))
        assertEquals("変化なし", s.change)
        assertNull(s.changeUp)
    }

    @Test fun aSingleSnapshotHasNoChangeLine() {
        val s = ready(history(50_000.0))
        assertNull(s.change)
        assertNull(s.changeUp)
    }

    @Test fun changeFromAZeroBaselineOmitsThePercentage() =
        assertEquals("+500ポンド", ready(history(0.0, 500.0)).change)

    // ---- milestones ------------------------------------------------------------------------------------

    @Test fun milestonesWithContributionsMatchTheDashboard() {
        val m = ready(history(183_000.0, 184_320.0)).milestones
        assertEquals(listOf("10万ポンド", "25万ポンド", "50万ポンド", "75万ポンド", "100万ポンド"), m.map { it.label })
        assertEquals("達成 (30歳)", m[0].value)
        assertTrue(m[0].reached)
        assertEquals("2年6か月 (32歳)", m[1].value)
        assertEquals(false, m[1].reached)
    }

    @Test fun milestonesWithoutContributionsMatchTheDashboard() {
        val m = ready(history(184_320.0), WidgetConfig(withContributions = false)).milestones
        assertEquals("4年7か月 (34歳)", m[1].value)
        assertEquals("25年0か月 (55歳)", m[4].value)
    }

    @Test fun aMissingMonthlyContributionActsLikeZero() {
        val m = ready(history(184_320.0, monthly = null)).milestones
        assertEquals("4年7か月 (34歳)", m[1].value)
    }

    @Test fun nextMilestonesSkipsTheOnesAlreadyReached() {
        val s = ready(history(184_320.0))
        assertEquals(listOf("25万ポンド", "50万ポンド", "75万ポンド"), s.nextMilestones(3).map { it.label })
    }

    @Test fun nextMilestonesIsEmptyWhenEverythingIsReached() =
        assertTrue(ready(history(2_000_000.0)).nextMilestones(3).isEmpty())

    // ---- projections and the one-year estimate ----------------------------------------------------------

    @Test fun projectionsFollowTheContributionSetting() {
        val h = history(184_320.0)
        assertEquals(
            listOf("40歳" to "100万ポンド", "50歳" to "170万ポンド", "60歳" to "290万ポンド", "65歳" to "410万ポンド"),
            ready(h).projections.map { it.label to it.value },
        )
        assertEquals(
            listOf("40歳" to "90万ポンド", "50歳" to "150万ポンド", "60歳" to "250万ポンド", "65歳" to "350万ポンド"),
            ready(h, WidgetConfig(withContributions = false)).projections.map { it.label to it.value },
        )
    }

    @Test fun nullProjectionsAreOmittedAndNeverCrash() {
        val s = ready(history(184_320.0, oneYear = null, wc40 = null, wc50 = null, wc60 = null, wc65 = null))
        assertTrue(s.projections.isEmpty())
        assertNull(s.oneYear)
        assertEquals(5, s.milestones.size)          // milestones still work without projections
    }

    @Test fun oneYearEstimateIsCompact() = assertEquals("21万ポンド", ready(history(184_320.0)).oneYear)

    // ---- zero, negative, odd currencies ----------------------------------------------------------------

    @Test fun negativeNetWorthFormatsWithAMinusAndNeverReachesWithoutContributions() {
        val s = ready(history(-1_500.0), WidgetConfig(withContributions = false))
        assertEquals("-1,500ポンド", s.headline)
        assertTrue(s.milestones.all { it.value == "100年以上" && !it.reached })
    }

    @Test fun zeroNetWorthDoesNotCrash() {
        val s = ready(history(0.0), WidgetConfig(withContributions = false))
        assertEquals("0ポンド", s.headline)
        assertEquals("100年以上", s.milestones[0].value)
    }

    @Test fun anUnknownCurrencyUsesItsCode() = assertEquals("1,000 CHF", ready(history(1_000.0, currency = "CHF")).headline)

    @Test fun aBlankCurrencyHasNoUnit() = assertEquals("1,000", ready(history(1_000.0, currency = "")).headline)

    // ---- freshness and hints ---------------------------------------------------------------------------

    @Test fun updatedTextIsRelative() {
        assertEquals("たった今更新", ready(history(1.0), fetchedAt = nowMs - 20_000).updated)
        assertEquals("5分前に更新", ready(history(1.0), fetchedAt = nowMs - 5 * 60_000).updated)
        assertEquals("3時間前に更新", ready(history(1.0), fetchedAt = nowMs - 3 * hour).updated)
        assertEquals("2日前に更新", ready(history(1.0), fetchedAt = nowMs - 49 * hour).updated)
    }

    @Test fun aClockSkewedFutureFetchTimeReadsAsJustNow() =
        assertEquals("たった今更新", ready(history(1.0), fetchedAt = nowMs + hour).updated)

    @Test fun noHintWhenFresh() = assertNull(ready(history(1.0), fetchedAt = nowMs - 5 * hour).hint)

    @Test fun staleDataGetsAnOutOfDateHintButStaysOnScreen() {
        val s = ready(history(184_320.0), fetchedAt = nowMs - 7 * hour)
        assertEquals("古いデータ — サーバーに接続できません", s.hint)
        assertEquals("184,320ポンド", s.headline)
    }

    @Test fun aRejectedKeyKeepsTheLastDataAndShowsAHint() {
        val s = ready(history(184_320.0), status = rejected)
        assertEquals("キーが拒否されました — アプリで更新してください", s.hint)
        assertEquals("184,320ポンド", s.headline)
    }

    // ---- assumptions line ------------------------------------------------------------------------------

    @Test fun assumptionMentionsTheReturnAndTheMonthlyContribution() {
        assertEquals("年7%運用・毎月1,000ポンド積立", ready(history(184_320.0)).assumption)
        assertEquals("年7%運用・積立なし", ready(history(184_320.0), WidgetConfig(withContributions = false)).assumption)
        assertEquals("年7%運用・積立なし", ready(history(184_320.0, monthly = null)).assumption)
    }

    @Test fun headlineIsSplitIntoAmountAndUnit() {
        val s = ready(history(184_320.0))
        assertEquals("184,320", s.headlineAmount)
        assertEquals("ポンド", s.headlineUnit)
    }

    // ---- wide layout extras ----------------------------------------------------------------------------

    private fun dated(vararg daysAgoAndTotal: Pair<Int, Double>) = HistoryResponse(
        snapshots = daysAgoAndTotal.mapIndexed { i, (d, t) ->
            Snapshot(id = i.toLong(), timestamp = now.minusSeconds(d * 86_400L).toString(), currency = "GBP", grandTotal = t)
        },
        estimatedNetWorth1y = 210_000.0, monthlyContribution = 1_000.0,
    )

    @Test fun nextGoalShowsBothProgressBars() {
        val g = ready(history(48_000.0)).nextGoal!!            // 7% a year, £1,000 a month
        assertEquals("10万ポンド", g.label)
        assertEquals("59.1%", g.growth.percent)
        assertEquals(0.591f, g.growth.fraction, 0.001f)
        assertEquals("53.6%", g.time!!.percent)
        assertEquals("あと3年1か月", g.remaining)                 // 37 months, the same figure as the milestone rows
    }

    @Test fun nextGoalProgressDoesNotDependOnHowLongYouHaveBeenTracking() {
        val young = ready(dated(3 to 40_000.0, 0 to 48_000.0)).nextGoal!!
        val old = ready(dated(2_280 to 1_900.0, 0 to 48_000.0)).nextGoal!!
        assertEquals(young, old)
    }

    @Test fun withoutContributionsTimeProgressIsNotAvailable() {
        val g = ready(history(48_000.0), WidgetConfig(withContributions = false)).nextGoal!!
        assertEquals("100.0%", g.growth.percent)
        assertNull(g.time)
    }

    @Test fun nextGoalWithNoWayToReachItSaysSo() {
        val g = ready(history(0.0), WidgetConfig(withContributions = false)).nextGoal!!
        assertEquals("0.0%", g.growth.percent)
        assertEquals("あと100年以上", g.remaining)
    }

    @Test fun nextGoalSkipsReachedMilestonesAndIsNullWhenAllAreReached() {
        assertEquals("25万ポンド", ready(history(184_320.0)).nextGoal!!.label)
        assertNull(ready(history(2_000_000.0)).nextGoal)
    }

    @Test fun periodChangesCompareAgainstTheLatestSnapshotAtOrBeforeEachCutoff() {
        val s = ready(dated(40 to 40_000.0, 30 to 44_000.0, 8 to 46_000.0, 0 to 48_000.0))
        assertEquals(listOf("7日間", "30日間", "全期間"), s.periodChanges.map { it.label })
        assertEquals("+2,000ポンド (+4.3%)", s.periodChanges[0].text)     // 8 days ago: 46,000
        assertEquals("+4,000ポンド (+9.1%)", s.periodChanges[1].text)     // 30 days ago: 44,000
        assertEquals("+8,000ポンド (+20.0%)", s.periodChanges[2].text)    // first snapshot: 40,000
        assertTrue(s.periodChanges.all { it.up == true })
    }

    @Test fun periodsLongerThanTheHistoryAreOmitted() {
        val s = ready(dated(3 to 100.0, 0 to 90.0))
        assertEquals(listOf("全期間"), s.periodChanges.map { it.label })
        assertEquals(false, s.periodChanges[0].up)
    }

    @Test fun aSingleSnapshotHasNoPeriodChangesAndUnparseableTimestampsAreIgnored() {
        assertTrue(ready(dated(0 to 100.0)).periodChanges.isEmpty())
        val bad = HistoryResponse(snapshots = listOf(Snapshot(1, "nope", "GBP", 1.0), Snapshot(2, "nah", "GBP", 2.0)))
        val s = ready(bad)
        assertTrue(s.periodChanges.isEmpty())
    }

    @Test fun peakIsTheHighestSnapshotEver() =
        assertEquals("最高値 50,000ポンド", ready(dated(9 to 40_000.0, 5 to 50_000.0, 0 to 48_000.0)).peak)

    // ---- daily spend and payday ------------------------------------------------------------------------

    private fun withBank(chase: Double?, amex: Double?) = HistoryResponse(
        snapshots = listOf(Snapshot(1, "2026-09-29T07:00:00Z", "GBP", 50_000.0, chase, amex)),
    )

    @Test fun theWidgetShowsDaysToPayday() {
        val s = ready(history(1.0))
        assertEquals(23, s.daysToPayday)
        assertEquals("給料日まで23日", s.payday.text)
        assertEquals(StatLine("給料日まで", "23", "日", false), s.payday)
    }

    @Test fun maxDailySpendWithAndWithoutTheInvestBuffer() {
        // 2026-09-30 -> payday Fri 2026-10-23 = 23 days. (2,100 - 450) / 23 = 71.74; minus 400 buffer: 1,250 / 23 = 54.35.
        val s = ready(withBank(2_100.0, 450.0))
        assertEquals("1日の上限 72ポンド", s.maxDailySpend?.text)
        assertEquals("400ポンド確保で 54ポンド", s.maxDailySpendBuffer?.text)
    }

    @Test fun maxDailySpendCanGoNegative() {
        val line = ready(withBank(500.0, 450.0)).maxDailySpendBuffer!!
        assertEquals("400ポンド確保で -15ポンド", line.text)
        assertTrue(line.negative)
    }

    @Test fun noDailySpendWhenTheSnapshotLacksChaseOrAmex() {
        assertNull(ready(withBank(null, null)).maxDailySpend)
        assertNull(ready(withBank(2_100.0, null)).maxDailySpendBuffer)
    }

    @Test fun snapshotDecodesChaseAndAmexAndToleratesTheirAbsence() {
        val full = ApiJson.decodeFromString(Snapshot.serializer(), """{"grandTotal":1,"chaseTotal":2.5,"amexDebtTotal":3}""")
        assertEquals(2.5, full.chaseTotal!!, 0.0)
        assertEquals(3.0, full.amexDebtTotal!!, 0.0)
        val old = ApiJson.decodeFromString(Snapshot.serializer(), """{"grandTotal":1,"chaseTotal":null}""")
        assertNull(old.chaseTotal)
        assertNull(old.amexDebtTotal)
    }

    // ---- peak ------------------------------------------------------------------------------------------

    @Test fun peakUsesTheServersPeakWhenTheThinnedSnapshotsMissIt() =
        assertEquals("最高値 52,000ポンド", ready(history(48_000.0, 49_000.0).copy(peakNetWorth = 52_000.0)).peak)

    @Test fun peakFallsBackToTheSnapshotsWhenTheServerSendsNone() =
        assertEquals("最高値 49,000ポンド", ready(history(48_000.0, 49_000.0)).peak)

    // ---- breakeven -------------------------------------------------------------------------------------

    @Test fun breakevenIsTheNetWorthWhereAMonthOfReturnEqualsTheContribution() {
        // 1,000 / (1.07^(1/12) - 1) = 176,865; from 49,000 that is 127,865 to go.
        val b = ready(history(49_000.0)).breakeven!!
        assertEquals("17.7万ポンド", b.label)
        assertEquals("あと12.8万ポンド", b.remaining)
        assertNotNull(b.time)
    }

    @Test fun breakevenIsReachedOncePastIt() {
        val b = ready(history(200_000.0)).breakeven!!
        assertEquals("達成済み", b.remaining)
        assertEquals(1f, b.growth.fraction, 0f)
    }

    @Test fun noBreakevenWithoutContributions() {
        assertNull(ready(history(49_000.0), WidgetConfig(withContributions = false)).breakeven)
        assertNull(ready(history(49_000.0, monthly = 0.0)).breakeven)
    }

    @Test fun projectionTableShowsBothScenarios() {
        val t = ready(history(184_320.0)).projectionTable
        assertEquals(listOf("40歳", "50歳", "60歳", "65歳"), t.map { it.label })
        assertEquals("100万ポンド", t[0].withContributions)
        assertEquals("90万ポンド", t[0].withoutContributions)
    }

    @Test fun projectionTableUsesADashForAMissingScenario() {
        val t = ready(history(184_320.0, wc40 = null)).projectionTable
        assertEquals("-", t[0].withContributions)
        assertEquals("90万ポンド", t[0].withoutContributions)
    }
}
