package com.isym444.networth.domain

import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.CacheSnapshot
import com.isym444.networth.data.HistoryResponse
import com.isym444.networth.data.SyncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

data class MilestoneRow(val label: String, val value: String, val reached: Boolean)
data class ProjectionRow(val label: String, val value: String)
data class ProjectionCompareRow(val label: String, val withContributions: String, val withoutContributions: String)
data class PeriodChange(val label: String, val text: String, val up: Boolean?)
data class TrendPoint(val epochMs: Long, val total: Double)
data class GoalBar(val fraction: Float, val percent: String)
data class NextGoal(
    val label: String,
    val remaining: String,          // "あと3年1か月"
    val growth: GoalBar,            // share of the target that comes from money you already hold (資産の寄与)
    val time: GoalBar?,             // share of the journey from zero already behind you (時間の進捗); null when undefined
)

sealed interface WidgetState {
    data object NotConfigured : WidgetState
    data class Empty(val message: String) : WidgetState
    data class Ready(
        val headlineAmount: String,         // "184,320"
        val headlineUnit: String,           // "ポンド" (drawn smaller, next to the amount)
        val change: String?,                // "+1,234ポンド (+0.7%)" / "変化なし" / null when there is no previous snapshot
        val changeUp: Boolean?,             // true = up, false = down, null = neutral
        val updated: String,                // "3時間前に更新"
        val hint: String?,                  // "キーが拒否されました — …" / "古いデータ — …" / null
        val oneYear: String?,               // "21万ポンド"
        val assumption: String,             // "年7%運用・毎月1,000ポンド積立"
        val milestones: List<MilestoneRow>, // all five, in order
        val projections: List<ProjectionRow>,
        val projectionTable: List<ProjectionCompareRow>, // both scenarios side by side (wide layout)
        val nextGoal: NextGoal?,                         // two progress measures to the next unreached milestone; null when all are reached
        val periodChanges: List<PeriodChange>,           // 7 days / 30 days / since the first snapshot ("全期間"), where history allows
        val peak: String?,                               // "最高値 49,153ポンド"
        val trend: List<TrendPoint>,                     // totals over the last two years, oldest first (chart)
        val trendSpan: String,                           // how much time `trend` covers: "2年間", "5か月間", "12日間"
    ) : WidgetState {
        val headline: String get() = headlineAmount + headlineUnit

        /** The next `n` milestones that are not yet reached (for the medium layout). */
        fun nextMilestones(n: Int): List<MilestoneRow> = milestones.filter { !it.reached }.take(n)
    }
}

/** Pure: (cache, config, settings, sync status, now, zone) -> what to draw. No Android, no I/O. */
object WidgetModel {
    const val STALE_AFTER_MS: Long = 6L * 60 * 60 * 1000
    const val TREND_WINDOW_DAYS = 730L      // the chart covers the last two years...
    const val TREND_MAX_POINTS = 200        // ...thinned to this many points (the chart is only a few hundred px wide)

    fun build(
        cache: CacheSnapshot,
        config: WidgetConfig,
        settings: AppSettings,
        status: SyncStatus,
        now: Instant,
        zone: ZoneId,
    ): WidgetState {
        val history = cache.history
        if (history == null) {
            return when {
                !settings.isConfigured -> WidgetState.NotConfigured
                status.keyRejected -> WidgetState.Empty("キーが拒否されました — アプリを開いて修正してください")
                else -> WidgetState.Empty("初回の同期を待っています")
            }
        }
        val latest = history.snapshots.lastOrNull() ?: return WidgetState.Empty("データがまだありません")
        val previous = history.snapshots.getOrNull(history.snapshots.size - 2)
        val unit = Money.unit(latest.currency)
        val today = now.atZone(zone).toLocalDate()

        val delta = previous?.let { latest.grandTotal - it.grandTotal }
        val (change, up) = changeLine(delta, previous?.grandTotal, unit)

        val contribution = if (config.withContributions) history.monthlyContribution ?: 0.0 else 0.0
        val milestones = ProjectionConfig.MILESTONES.map { target ->
            val reach = Projection.reach(latest.grandTotal, target, contribution, today)
            MilestoneRow(Money.compact(target, unit), reach.text(), reach is Reach.Reached)
        }

        val points = history.snapshots
            .mapNotNull { snap -> runCatching { Instant.parse(snap.timestamp) }.getOrNull()?.let { it to snap.grandTotal } }
            .sortedBy { it.first }
        val trendPoints = trendPoints(points)

        val ageMs = now.toEpochMilli() - cache.fetchedAt
        val hint = when {
            status.keyRejected -> "キーが拒否されました — アプリで更新してください"
            cache.fetchedAt > 0 && ageMs > STALE_AFTER_MS -> "古いデータ — サーバーに接続できません"
            else -> null
        }

        return WidgetState.Ready(
            headlineAmount = Money.number(latest.grandTotal),
            headlineUnit = unit,
            change = change,
            changeUp = up,
            updated = updatedText(ageMs),
            hint = hint,
            oneYear = history.estimatedNetWorth1y?.let { Money.compact(it, unit) },
            assumption = assumption(contribution, unit),
            milestones = milestones,
            projections = projections(history, config.withContributions, unit),
            projectionTable = projectionTable(history, unit),
            nextGoal = nextGoal(latest.grandTotal, unit, contribution),
            periodChanges = periodChanges(points, unit),
            peak = points.maxByOrNull { it.second }?.let { "最高値 " + Money.full(it.second, unit) },
            trend = trendPoints.map { TrendPoint(it.first.toEpochMilli(), it.second) },
            trendSpan = trendPoints.firstOrNull()?.let { spanText(ChronoUnit.DAYS.between(it.first, trendPoints.last().first)) } ?: "",
        )
    }

    private fun changeLine(delta: Double?, previousTotal: Double?, unit: String): Pair<String?, Boolean?> {
        if (delta == null || previousTotal == null) return null to null
        if (delta.roundToLong() == 0L) return "変化なし" to null
        val pct = if (previousTotal != 0.0) {
            String.format(Locale.UK, " (%+.1f%%)", delta / previousTotal.absoluteValue * 100)
        } else ""
        val text = (if (delta > 0) "+" else "-") + Money.full(delta.absoluteValue, unit) + pct
        return text to (delta > 0)
    }

    private fun projections(h: HistoryResponse, withContributions: Boolean, unit: String): List<ProjectionRow> {
        val byAge = if (withContributions) {
            listOf(
                40 to h.expectedNetWorthWithContributionsAt40, 50 to h.expectedNetWorthWithContributionsAt50,
                60 to h.expectedNetWorthWithContributionsAt60, 65 to h.expectedNetWorthWithContributionsAt65,
            )
        } else {
            listOf(
                40 to h.expectedNetWorthAt40, 50 to h.expectedNetWorthAt50,
                60 to h.expectedNetWorthAt60, 65 to h.expectedNetWorthAt65,
            )
        }
        return byAge.mapNotNull { (age, value) -> value?.let { ProjectionRow("${age}歳", Money.compact(it, unit)) } }
    }

    /** Snapshots from the last [TREND_WINDOW_DAYS] days before the latest one, evenly thinned to [TREND_MAX_POINTS] (keeps first and last). */
    private fun trendPoints(points: List<Pair<Instant, Double>>): List<Pair<Instant, Double>> {
        val latestAt = points.lastOrNull()?.first ?: return emptyList()
        val recent = points.filter { it.first >= latestAt.minus(TREND_WINDOW_DAYS, ChronoUnit.DAYS) }
        if (recent.size <= TREND_MAX_POINTS) return recent
        return List(TREND_MAX_POINTS) { i -> recent[(i.toLong() * (recent.size - 1) / (TREND_MAX_POINTS - 1)).toInt()] }
    }

    private fun spanText(days: Long): String = when {
        days < 60 -> "${days}日間"
        days < 700 -> "${(days / 30.4).roundToLong()}か月間"
        else -> "${(days / 365.25).roundToLong()}年間"
    }

    /** Change over the last 7 and 30 days (only when history goes back that far) and since the first snapshot ("全期間"). */
    private fun periodChanges(points: List<Pair<Instant, Double>>, unit: String): List<PeriodChange> {
        if (points.size < 2) return emptyList()
        val (latestAt, latestTotal) = points.last()
        val out = mutableListOf<PeriodChange>()
        for ((days, label) in listOf(7L to "7日間", 30L to "30日間")) {
            val base = points.lastOrNull { it.first <= latestAt.minus(days, ChronoUnit.DAYS) } ?: continue
            val (text, up) = changeLine(latestTotal - base.second, base.second, unit)
            if (text != null) out += PeriodChange(label, text, up)
        }
        val first = points.first().second
        val (text, up) = changeLine(latestTotal - first, first, unit)
        if (text != null) out += PeriodChange("全期間", text, up)
        return out
    }

    private fun nextGoal(total: Double, unit: String, contribution: Double): NextGoal? {
        val target = ProjectionConfig.MILESTONES.firstOrNull { it > total } ?: return null
        val progress = GoalProgress.compute(total, target, contribution, ProjectionConfig.ANNUAL_RETURN)
        val monthsLeft = Projection.monthsToReach(total, target, contribution)      // same figure as the milestone rows
        val remaining = monthsLeft?.let { "あと${it / 12}年${it % 12}か月" } ?: "あと100年以上"
        return NextGoal(Money.compact(target, unit), remaining, goalBar(progress.growthShare), progress.timeShare?.let(::goalBar))
    }

    private fun goalBar(share: Double) = GoalBar(share.toFloat(), String.format(Locale.UK, "%.1f%%", share * 100))

    private fun projectionTable(h: HistoryResponse, unit: String): List<ProjectionCompareRow> {
        fun cell(v: Double?) = v?.let { Money.compact(it, unit) } ?: "-"
        return listOf(
            Triple(40, h.expectedNetWorthWithContributionsAt40, h.expectedNetWorthAt40),
            Triple(50, h.expectedNetWorthWithContributionsAt50, h.expectedNetWorthAt50),
            Triple(60, h.expectedNetWorthWithContributionsAt60, h.expectedNetWorthAt60),
            Triple(65, h.expectedNetWorthWithContributionsAt65, h.expectedNetWorthAt65),
        ).filter { it.second != null || it.third != null }
            .map { (age, with, without) -> ProjectionCompareRow("${age}歳", cell(with), cell(without)) }
    }

    private fun assumption(contribution: Double, unit: String): String {
        val rate = "年${(ProjectionConfig.ANNUAL_RETURN * 100).roundToLong()}%運用"
        return if (contribution > 0) "$rate・毎月${Money.full(contribution, unit)}積立" else "$rate・積立なし"
    }

    private fun updatedText(ms: Long): String = when {
        ms < 60_000 -> "たった今更新"                       // also covers a fetch time in the future (clock skew)
        ms < 3_600_000 -> "${ms / 60_000}分前に更新"
        ms < 86_400_000 -> "${ms / 3_600_000}時間前に更新"
        else -> "${ms / 86_400_000}日前に更新"
    }
}
