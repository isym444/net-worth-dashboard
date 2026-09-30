package com.isym444.networth.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import com.isym444.networth.domain.MilestoneRow
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
                WidgetSize.LARGE -> LargeLayout(state)
            }
            is WidgetState.Empty -> Message(state.message)
            WidgetState.NotConfigured -> Message("Open Net Worth to set up")
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
        Label("Net worth")
        Text(s.headline, style = TextStyle(color = Palette.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        ChangeLine(s)
        Spacer(GlanceModifier.height(4.dp))
        Footer(s)
    }
}

@Composable
private fun MediumLayout(s: WidgetState.Ready) {
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Label("Net worth")
            Text(s.headline, style = TextStyle(color = Palette.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            ChangeLine(s)
            Spacer(GlanceModifier.height(4.dp))
            Footer(s)
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            val next = s.nextMilestones(3)
            if (next.isEmpty()) {
                Text("All milestones reached", style = TextStyle(color = Palette.up, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            } else {
                Label("Next milestones")
                next.forEach { MilestoneLine(it) }
            }
        }
    }
}

@Composable
private fun LargeLayout(s: WidgetState.Ready) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Label("Net worth")
        Text(s.headline, style = TextStyle(color = Palette.primary, fontSize = 30.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        ChangeLine(s)
        s.oneYear?.let { Text("In a year ≈ $it", style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1) }
        Spacer(GlanceModifier.height(8.dp))
        Label("Time to milestones")
        s.milestones.forEach { MilestoneLine(it) }
        if (s.projections.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            Label("Projected net worth")
            s.projections.forEach { ProjectionLine(it) }
        }
        Spacer(GlanceModifier.height(6.dp))
        Footer(s)
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
private fun MilestoneLine(m: MilestoneRow) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            m.label,
            modifier = GlanceModifier.width(56.dp),
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
            modifier = GlanceModifier.width(56.dp),
            style = TextStyle(color = Palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Text(p.value, style = TextStyle(color = Palette.secondary, fontSize = 12.sp), maxLines = 1)
    }
}
