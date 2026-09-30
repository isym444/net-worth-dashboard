package com.isym444.networth.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.isym444.networth.data.AppGraph
import com.isym444.networth.data.AppSettings
import com.isym444.networth.data.Field
import com.isym444.networth.data.SettingsValidator
import com.isym444.networth.data.SyncResult
import com.isym444.networth.data.Validation
import com.isym444.networth.domain.Money
import com.isym444.networth.widget.Scheduler
import com.isym444.networth.widget.WidgetPinner
import com.isym444.networth.widget.WidgetUpdater
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureScheduled(this)
        setContent { AppTheme { SettingsScreen() } }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val scope = rememberCoroutineScope()
    val saved = remember { graph.settings.load() }

    var baseUrl by remember { mutableStateOf(saved.baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL }) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var webUrl by remember { mutableStateOf(saved.webUrl) }
    var errors by remember { mutableStateOf<Map<Field, String>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(syncSummary(graph)) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Net Worth", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API URL") },
            placeholder = { Text(AppSettings.DEFAULT_BASE_URL) }, singleLine = true,
            isError = Field.BASE_URL in errors, supportingText = { errors[Field.BASE_URL]?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it }, label = { Text("Dashboard access key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = Field.API_KEY in errors, supportingText = { errors[Field.API_KEY]?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = webUrl, onValueChange = { webUrl = it }, label = { Text("Dashboard URL (opened when you tap the widget)") },
            singleLine = true, isError = Field.WEB_URL in errors, supportingText = { errors[Field.WEB_URL]?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                when (val v = SettingsValidator.validate(baseUrl, apiKey, webUrl)) {
                    is Validation.Invalid -> { errors = v.errors; status = null }
                    is Validation.Valid -> {
                        errors = emptyMap(); busy = true; status = "Connecting…"
                        scope.launch {
                            graph.settings.save(v.settings)
                            Scheduler.ensureScheduled(context)
                            Scheduler.refreshNow(context)      // survives the user leaving this screen mid-test
                            status = when (val result = graph.sync().refresh()) {
                                is SyncResult.Failed -> result.error.userMessage
                                SyncResult.Updated -> graph.cache.load().history?.snapshots?.lastOrNull()
                                    ?.let { "OK — latest ${Money.full(it.grandTotal, Money.unit(it.currency))}" }
                                    ?: "OK — no snapshots yet"
                            }
                            WidgetUpdater.refreshAll(context)
                            summary = syncSummary(graph)
                            busy = false
                        }
                    }
                }
            },
        ) { Text("Save & test") }

        status?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp)) }
        Text(summary, style = MaterialTheme.typography.bodySmall)

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!WidgetPinner.pin(context)) {
                    status = "This launcher can't add widgets from here. Long-press the home screen → Widgets → Net Worth."
                }
            },
        ) { Text("Add widget to home screen") }

        Text("Keeping it fresh", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "The widget always shows the last synced data, even offline. " +
                "Some devices (Oppo/ColorOS) pause background refresh; allow Net Worth to run unrestricted to keep it syncing.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        ) { Text("Battery optimisation settings") }
    }
}

private fun syncSummary(graph: AppGraph): String {
    val snap = graph.cache.load()
    val st = graph.settings.status()
    val last = if (snap.fetchedAt <= 0) "Never synced"
    else "Last synced " + Instant.ofEpochMilli(snap.fetchedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH))
    return buildString {
        append(last)
        st.lastError?.let { append("\nLast error: $it") }
    }
}
