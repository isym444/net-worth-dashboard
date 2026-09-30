package com.isym444.networth.data

import android.content.Context

/** Process-wide wiring shared by the widget, the workers and the screens. */
class AppGraph private constructor(context: Context) {
    val settings = SettingsStore(context)
    val cache = HistoryCache.forContext(context)

    fun sync(): Sync = Sync(cache, source = { sourceFor(settings.load()) }, onResult = settings::recordSync)

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(context.applicationContext).also { instance = it } }

        fun sourceFor(s: AppSettings): HistorySource? {
            if (!s.isConfigured) return null
            val url = UrlPolicy.parse(s.baseUrl) ?: return null
            return ApiClient(url, s.apiKey)
        }
    }
}
