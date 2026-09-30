package com.isym444.networth.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class NetWorthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetWorthWidget()

    override fun onEnabled(context: Context) {          // first widget placed
        super.onEnabled(context)
        Scheduler.ensureScheduled(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {         // last widget removed
        super.onDisabled(context)
        Scheduler.cancelAll(context)
    }
}
