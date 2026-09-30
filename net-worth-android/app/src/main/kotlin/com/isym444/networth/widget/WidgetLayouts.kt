package com.isym444.networth.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.isym444.networth.domain.GoalBar
import com.isym444.networth.domain.MilestoneRow
import com.isym444.networth.domain.PeriodChange
import com.isym444.networth.domain.ProjectionCompareRow
import com.isym444.networth.domain.WIDE_CHART_MIN_HEIGHT_DP
import com.isym444.networth.domain.ProjectionRow
import com.isym444.networth.domain.WidgetSize
import com.isym444.networth.domain.WidgetState

internal object Palette {
    private fun solid(c: Color): ColorProvider = ColorProvider(c)
    private fun dayNight(day: Color, night: Color): ColorProvider = androidx.glance.color.ColorProvider(day, night)

    val bg = dayNight(Color(0xFFFFFFFF), Color(0xFF1C1C1E))
    val primary = dayNight(Color(0xFF111111), Color(0xFFF2F2F7))
    val secondary = dayNight(Color(0xFF6B6B70), Color(0xFF9A9AA0))
    val up = dayNight(Color(0xFF2E7D32), Color(0xFF66BB6A))
    val down = dayNight(Color(0xFFC62828), Color(0xFFEF5350))
    val warn = solid(Color(0xFFFB8C00))
    val accent = dayNight(Color(0xFF1E88E5), Color(0xFF64B5F6))
    val track = dayNight(Color(0xFFE3E3E8), Color(0xFF3A3A3F))
}

@Composable
internal fun WidgetContent(state: WidgetState, tap: Intent) {
    val open = actionStartActivity(tap)
    val size = LocalSize.current
    val layout = WidgetSize.forDp(size.width.value, size.height.value)
    Column(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
            .background(Palette.bg).cornerRadius(24.dp).padding(12.dp).clickable(open),
    ) {
        when (state) {
            is WidgetState.Ready -> when (layout) {
                WidgetSize.SMALL -> SmallLayout(state)
                WidgetSize.MEDIUM -> MediumLayout(state)
                WidgetSize.LARGE -> LargeLayout(state, showProjections = false)
                WidgetSize.XLARGE -> LargeLayout(state, showProjections = true)
                WidgetSize.WIDE -> WideLayout(state, showChart = size.height.value >= WIDE_CHART_MIN_HEIGHT_DP, widthDp = size.width.value)
            }
            is WidgetState.Empty -> Message(state.message)
            WidgetState.NotConfigured -> Message("アプリを開いて設定してください")
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = Palette.secondary, fontSize = 13.sp, textAlign = TextAlign.Center))
    }
}

@Composable
private fun SmallLayout(s: WidgetState.Ready) {
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Label("純資産")
        Headline(s, amountSize = 24.sp)
        ChangeLine(s)
        Spacer(GlanceModifier.height(4.dp))
        Footer(s)
    }
}

@Composable
private fun MediumLayout(s: WidgetState.Ready) {
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Label("純資産")
            Headline(s, amountSize = 20.sp)
            ChangeLine(s)
            Spacer(GlanceModifier.height(4.dp))
            Footer(s)
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            val next = s.nextMilestones(3)
            if (next.isEmpty()) {
                Text("全ての目標を達成しました", style = TextStyle(color = Palette.up, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            } else {
                Label("次の目標")
                next.forEach { MilestoneLine(it) }
            }
        }
    }
}

// Glance drops every child after the 10th in a Column/Row, so each block below is its own nested Column (well under 10
// children) and the outer Column has only a handful. Do not flatten these back together.
@Composable
private fun LargeLayout(s: WidgetState.Ready, showProjections: Boolean) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Column {
            Label("純資産")
            Headline(s, amountSize = 30.sp)
            ChangeLine(s)
            s.oneYear?.let { Text("1年後の予想 ≈ $it", style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1) }
        }
        Spacer(GlanceModifier.height(8.dp))
        Column {
            Label("目標までの期間")
            if (showProjections) Text(s.assumption, style = TextStyle(color = Palette.secondary, fontSize = 10.sp), maxLines = 1)
            s.milestones.forEach { MilestoneLine(it) }
        }
        if (showProjections && s.projections.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            Column {
                Label("将来の予想純資産")
                s.projections.forEach { ProjectionLine(it) }
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Footer(s)
    }
}

// Two columns: the summary, goal progress and recent changes on the left; milestones and both projection scenarios on
// the right; and, when tall enough, the trend chart under both. Each block is its own Column (Glance's 10-child limit).
@Composable
private fun WideLayout(s: WidgetState.Ready, showChart: Boolean, widthDp: Float) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Column {
                    Label("純資産")
                    Headline(s, amountSize = 30.sp)
                    ChangeLine(s)
                    s.oneYear?.let { Text("1年後の予想 ≈ $it", style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1) }
                }
                s.nextGoal?.let { goal ->
                    Spacer(GlanceModifier.height(8.dp))
                    Column {
                        Label("${goal.label}まで・${goal.remaining}")
                        Spacer(GlanceModifier.height(3.dp))
                        GoalBarRow("資産の寄与", goal.growth, Palette.up)
                        Spacer(GlanceModifier.height(3.dp))
                        GoalBarRow("時間の進捗", goal.time, Palette.accent)
                    }
                }
                if (s.periodChanges.isNotEmpty() || s.peak != null) {
                    Spacer(GlanceModifier.height(8.dp))
                    Column {
                        Label("増減の推移")
                        s.periodChanges.forEach { PeriodLine(it) }
                        s.peak?.let { Text(it, modifier = GlanceModifier.padding(vertical = 2.dp), style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1) }
                    }
                }
                Spacer(GlanceModifier.height(6.dp))
                Footer(s)
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Column {
                    Label("目標までの期間")
                    Text(s.assumption, style = TextStyle(color = Palette.secondary, fontSize = 10.sp), maxLines = 1)
                    s.milestones.forEach { MilestoneLine(it, labelWidth = 80.dp) }
                }
                if (s.projectionTable.isNotEmpty()) {
                    Spacer(GlanceModifier.height(8.dp))
                    Column {
                        Label("将来の予想純資産")
                        CompareHeader()
                        s.projectionTable.forEach { CompareLine(it) }
                    }
                }
            }
        }
        if (showChart && s.trend.size >= 2) {
            Spacer(GlanceModifier.height(8.dp))
            Label("推移 (過去${s.trendSpan})")
            TrendImage(s, widthDp - 24f)
        }
    }
}

@Composable
private fun GoalBarRow(label: String, bar: GoalBar?, color: ColorProvider) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = GlanceModifier.width(54.dp), style = TextStyle(color = Palette.secondary, fontSize = 10.sp), maxLines = 1)
        if (bar == null) {
            Text("算出不可", style = TextStyle(color = Palette.secondary, fontSize = 11.sp), maxLines = 1)
        } else {
            LinearProgressIndicator(
                progress = bar.fraction,
                modifier = GlanceModifier.defaultWeight().height(6.dp),
                color = color,
                backgroundColor = Palette.track,
            )
            Text(
                bar.percent,
                modifier = GlanceModifier.padding(start = 6.dp),
                style = TextStyle(color = Palette.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrendImage(s: WidgetState.Ready, widthDp: Float) {
    val density = LocalContext.current.resources.displayMetrics.density
    val up = s.trend.last().total >= s.trend.first().total
    val bitmap = remember(s.trend, widthDp) { TrendChart.render(s.trend, (widthDp * density).toInt(), (72 * density).toInt(), density, up) }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = GlanceModifier.fillMaxWidth().height(72.dp),
    )
}

@Composable
private fun PeriodLine(c: PeriodChange) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            c.label,
            modifier = GlanceModifier.width(48.dp),
            style = TextStyle(color = Palette.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(
            c.text,
            style = TextStyle(color = when (c.up) { true -> Palette.up; false -> Palette.down; null -> Palette.secondary }, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CompareHeader() {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Spacer(GlanceModifier.width(44.dp))
        Text("積立あり", modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = Palette.secondary, fontSize = 10.sp), maxLines = 1)
        Text("積立なし", modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = Palette.secondary, fontSize = 10.sp), maxLines = 1)
    }
}

@Composable
private fun CompareLine(p: ProjectionCompareRow) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            p.label,
            modifier = GlanceModifier.width(44.dp),
            style = TextStyle(color = Palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(p.withContributions, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = Palette.primary, fontSize = 12.sp), maxLines = 1)
        Text(p.withoutContributions, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1)
    }
}

@Composable
private fun Headline(s: WidgetState.Ready, amountSize: TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(s.headlineAmount, style = TextStyle(color = Palette.primary, fontSize = amountSize, fontWeight = FontWeight.Bold), maxLines = 1)
        Text(
            s.headlineUnit,
            modifier = GlanceModifier.padding(start = 3.dp, bottom = 2.dp),
            style = TextStyle(color = Palette.primary, fontSize = (amountSize.value * 0.5f).sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = TextStyle(color = Palette.secondary, fontSize = 11.sp), maxLines = 1)

@Composable
private fun ChangeLine(s: WidgetState.Ready) {
    val text = s.change ?: return
    val color = when (s.changeUp) {
        true -> Palette.up
        false -> Palette.down
        null -> Palette.secondary
    }
    Text(text, style = TextStyle(color = color, fontSize = 12.sp), maxLines = 1)
}

@Composable
private fun Footer(s: WidgetState.Ready) {
    val text = s.hint ?: s.updated
    val color = if (s.hint != null) Palette.warn else Palette.secondary
    Text(text, style = TextStyle(color = color, fontSize = 10.sp), maxLines = 1)
}

@Composable
private fun MilestoneLine(m: MilestoneRow, labelWidth: Dp = 84.dp) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            m.label,
            modifier = GlanceModifier.width(labelWidth),
            style = TextStyle(color = Palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(
            m.value,
            style = TextStyle(color = if (m.reached) Palette.up else Palette.secondary, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProjectionLine(p: ProjectionRow) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            p.label,
            modifier = GlanceModifier.width(84.dp),
            style = TextStyle(color = Palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(p.value, style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1)
    }
}
