package com.isym444.networth.domain

import com.isym444.networth.data.ApiJson
import kotlinx.serialization.Serializable

/** Per-widget options, stored as JSON in Glance's per-instance state. */
@Serializable
data class WidgetConfig(
    val withContributions: Boolean = true,
) {
    fun toJson(): String = ApiJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String?): WidgetConfig =
            if (json.isNullOrBlank()) WidgetConfig()
            else runCatching { ApiJson.decodeFromString(serializer(), json) }.getOrDefault(WidgetConfig())
    }
}
