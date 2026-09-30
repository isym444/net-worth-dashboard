package com.isym444.networth.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalises a user-typed URL. HTTPS is required except for local dev hosts. */
object UrlPolicy {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun parse(input: String): HttpUrl? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val url = s.toHttpUrlOrNull() ?: return null
        if (url.scheme == "http" && url.host !in LOCAL_HOSTS) return null
        return url
    }
}
