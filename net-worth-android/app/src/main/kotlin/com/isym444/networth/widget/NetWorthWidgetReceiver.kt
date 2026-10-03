package com.isym444.networth.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

open class NetWorthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetWorthWidget()

    override fun onEnabled(context: Context) {          // first widget placed
        super.onEnabled(context)
        Scheduler.ensureScheduled(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {         // last widget removed
        super.onDisabled(context)
        // Two widget entries share this code; keep refreshing while any widget of either kind is still placed.
        val manager = AppWidgetManager.getInstance(context)
        val remaining = listOf(NetWorthWidgetReceiver::class.java, NetWorthFullWidgetReceiver::class.java)
            .sumOf { manager.getAppWidgetIds(ComponentName(context, it)).size }
        if (remaining == 0) Scheduler.cancelAll(context)
    }
}

/** The "full screen" entry in the widget picker: the same widget, declared tall (see `net_worth_widget_full_info.xml`). */
class NetWorthFullWidgetReceiver : NetWorthWidgetReceiver()
