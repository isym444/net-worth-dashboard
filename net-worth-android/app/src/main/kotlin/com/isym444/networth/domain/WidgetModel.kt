package com.isym444.networth.domain

import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.CacheSnapshot
import com.isym444.networth.data.HistoryResponse
import com.isym444.networth.data.SyncStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

data class MilestoneRow(val label: String, val value: String, val reached: Boolean)
data class ProjectionRow(val label: String, val value: String)

sealed interface WidgetState {
    data object NotConfigured : WidgetState
    data class Empty(val message: String) : WidgetState
    data class Ready(
        val headline: String,               // "£184,320"
        val change: String?,                // "+£1,234 (+0.7%)" / "No change" / null when there is no previous snapshot
        val changeUp: Boolean?,             // true = up, false = down, null = neutral
        val updated: String,                // "Updated 3h ago"
        val hint: String?,                  // "Key rejected — …" / "Out of date — …" / null
        val oneYear: String?,               // "£210k"
        val milestones: List<MilestoneRow>, // all five, in order
        val projections: List<ProjectionRow>,
    ) : WidgetState {
        /** The next `n` milestones that are not yet reached (for the medium layout). */
        fun nextMilestones(n: Int): List<MilestoneRow> = milestones.filter { !it.reached }.take(n)
    }
}

/** Pure: (cache, config, settings, sync status, now, zone) -> what to draw. No Android, no I/O. */
object WidgetModel {
    const val STALE_AFTER_MS: Long = 6L * 60 * 60 * 1000

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
                status.keyRejected -> WidgetState.Empty("Key rejected — open Net Worth to fix it")
                else -> WidgetState.Empty("Waiting for first sync")
            }
        }
        val latest = history.snapshots.lastOrNull() ?: return WidgetState.Empty("No snapshots yet")
        val previous = history.snapshots.getOrNull(history.snapshots.size - 2)
        val symbol = Money.symbol(latest.currency)
        val today = now.atZone(zone).toLocalDate()

        val delta = previous?.let { latest.grandTotal - it.grandTotal }
        val (change, up) = changeLine(delta, previous?.grandTotal, symbol)

        val contribution = if (config.withContributions) history.monthlyContribution ?: 0.0 else 0.0
        val milestones = ProjectionConfig.MILESTONES.map { target ->
            val reach = Projection.reach(latest.grandTotal, target, contribution, today)
            MilestoneRow(Money.compact(target, symbol), reach.text(), reach is Reach.Reached)
        }

        val ageMs = now.toEpochMilli() - cache.fetchedAt
        val hint = when {
            status.keyRejected -> "Key rejected — update it in the app"
            cache.fetchedAt > 0 && ageMs > STALE_AFTER_MS -> "Out of date — can't reach the server"
            else -> null
        }

        return WidgetState.Ready(
            headline = Money.full(latest.grandTotal, symbol),
            change = change,
            changeUp = up,
            updated = "Updated " + ago(ageMs),
            hint = hint,
            oneYear = history.estimatedNetWorth1y?.let { Money.compact(it, symbol) },
            milestones = milestones,
            projections = projections(history, config.withContributions, symbol),
        )
    }

    private fun changeLine(delta: Double?, previousTotal: Double?, symbol: String): Pair<String?, Boolean?> {
        if (delta == null || previousTotal == null) return null to null
        if (delta.roundToLong() == 0L) return "No change" to null
        val pct = if (previousTotal != 0.0) {
            String.format(Locale.UK, " (%+.1f%%)", delta / previousTotal.absoluteValue * 100)
        } else ""
        val text = (if (delta > 0) "+" else "-") + Money.full(delta.absoluteValue, symbol) + pct
        return text to (delta > 0)
    }

    private fun projections(h: HistoryResponse, withContributions: Boolean, symbol: String): List<ProjectionRow> {
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
        return byAge.mapNotNull { (age, value) -> value?.let { ProjectionRow("Age $age", Money.compact(it, symbol)) } }
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "just now"                       // also covers a fetch time in the future (clock skew)
        ms < 3_600_000 -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else -> "${ms / 86_400_000}d ago"
    }
}
