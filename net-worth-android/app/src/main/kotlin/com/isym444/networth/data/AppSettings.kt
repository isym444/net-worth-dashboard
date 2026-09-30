package com.isym444.networth.data

data class AppSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val webUrl: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://nwapi.isym.uk"
    }
}

/** Outcome of the last sync attempt, for the widget's hint line and the settings screen. */
data class SyncStatus(val lastSuccessAt: Long, val lastError: String?, val keyRejected: Boolean)

enum class Field { BASE_URL, API_KEY, WEB_URL }

sealed interface Validation {
    data class Valid(val settings: AppSettings) : Validation
    data class Invalid(val errors: Map<Field, String>) : Validation
}

object SettingsValidator {
    fun validate(baseUrl: String, apiKey: String, webUrl: String): Validation {
        val errors = linkedMapOf<Field, String>()

        val base = UrlPolicy.parse(baseUrl)
        if (base == null) errors[Field.BASE_URL] = "Enter a valid address. https is required (for example https://nwapi.example.com)."

        val key = apiKey.trim()
        when {
            key.isEmpty() -> errors[Field.API_KEY] = "Paste the dashboard access key."
            key.any { it.isWhitespace() } -> errors[Field.API_KEY] = "The key must not contain spaces."
        }

        val web = webUrl.trim()
        val webParsed = if (web.isEmpty()) null else UrlPolicy.parse(web)
        if (web.isNotEmpty() && webParsed == null) errors[Field.WEB_URL] = "Enter a valid https address, or leave blank."

        if (errors.isNotEmpty() || base == null) return Validation.Invalid(errors)
        return Validation.Valid(AppSettings(base.toString(), key, webParsed?.toString().orEmpty()))
    }
}
