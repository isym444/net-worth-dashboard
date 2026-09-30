package com.isym444.networth.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.isym444.networth.domain.WidgetConfig
import com.isym444.networth.ui.AppTheme
import kotlinx.coroutines.launch

/** Per-widget options. Launched by the launcher when the widget is added, and from its reconfigure action. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContent {
            AppTheme {
                val scope = rememberCoroutineScope()
                var config by remember { mutableStateOf(WidgetConfig()) }
                LaunchedEffect(Unit) { config = loadConfig(appWidgetId) }
                ConfigScreen(config, onChange = { config = it }, onSave = { scope.launch { save(appWidgetId, config) } })
            }
        }
    }

    private suspend fun loadConfig(appWidgetId: Int): WidgetConfig = runCatching {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        val prefs: Preferences = getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)
        WidgetConfig.fromJson(prefs[NetWorthWidget.CONFIG_KEY])
    }.getOrDefault(WidgetConfig())

    private suspend fun save(appWidgetId: Int, config: WidgetConfig) {
        runCatching {
            val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this, glanceId) {
                it[NetWorthWidget.CONFIG_KEY] = config.toJson()
                it[NetWorthWidget.TICK] = System.currentTimeMillis()
            }
            NetWorthWidget().update(this, glanceId)
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@Composable
private fun ConfigScreen(config: WidgetConfig, onChange: (WidgetConfig) -> Unit, onSave: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Widget options", style = MaterialTheme.typography.headlineSmall)

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Include monthly contributions in milestone times")
            Switch(checked = config.withContributions, onCheckedChange = { onChange(config.copy(withContributions = it)) })
        }
        Text(
            "On: uses the monthly contribution saved on the dashboard. Off: growth on today's net worth only.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
