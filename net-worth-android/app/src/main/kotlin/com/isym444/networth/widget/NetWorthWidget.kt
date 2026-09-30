package com.isym444.networth.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import com.isym444.networth.data.AppGraph
import com.isym444.networth.data.UrlPolicy
import com.isym444.networth.domain.WidgetConfig
import com.isym444.networth.domain.WidgetModel
import java.time.Instant
import java.time.ZoneId

/**
 * Renders from the cache only (never the network). Each update writes a new TICK into the widget's state,
 * which re-runs the `remember(tick)` blocks below: cache, settings and "now" are re-read, so fresh syncs and
 * the passage of time show up without restarting the session.
 */
class NetWorthWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = AppGraph.get(context)
        // Self-heal: a force-stop or ColorOS "clean up" drops the jobs; any render re-asserts them (idempotent).
        Scheduler.ensureScheduled(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val tick = prefs[TICK]
            val configJson = prefs[CONFIG_KEY]
            val config = remember(configJson) { WidgetConfig.fromJson(configJson) }
            val settings = remember(tick) { graph.settings.load() }
            val status = remember(tick) { graph.settings.status() }
            val cache = remember(tick) { graph.cache.load() }
            val now = remember(tick) { Instant.now() }
            val state = remember(tick, configJson) {
                WidgetModel.build(cache, config, settings, status, now, ZoneId.systemDefault())
            }
            val tap = remember(settings.webUrl) { tapIntent(context, settings.webUrl) }
            WidgetContent(state, tap)
        }
    }

    /** Tap: the web dashboard if configured, otherwise this app's launcher activity (the settings screen). */
    private fun tapIntent(context: Context, webUrl: String): Intent {
        val url = webUrl.takeIf { it.isNotBlank() }?.let { UrlPolicy.parse(it) }
        return if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
        else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
    }

    companion object {
        val CONFIG_KEY = stringPreferencesKey("config")
        val TICK = longPreferencesKey("tick")
    }
}
