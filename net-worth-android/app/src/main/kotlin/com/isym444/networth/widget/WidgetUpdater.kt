package com.isym444.networth.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState

object WidgetUpdater {
    /** Re-render every placed widget from the cache (no network). */
    suspend fun refreshAll(context: Context) {
        val widget = NetWorthWidget()
        val ids = GlanceAppWidgetManager(context).getGlanceIds(NetWorthWidget::class.java)
        for (id in ids) {
            updateAppWidgetState(context, id) { it[NetWorthWidget.TICK] = System.currentTimeMillis() }
            widget.update(context, id)
        }
    }
}
