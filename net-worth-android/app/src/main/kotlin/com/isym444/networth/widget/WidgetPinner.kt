package com.isym444.networth.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

object WidgetPinner {
    /** Asks the launcher to add the widget. False if this launcher can't do it (add it by hand then). */
    fun pin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, NetWorthWidgetReceiver::class.java), null, null)
    }
}
