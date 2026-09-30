package com.isym444.networth.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiError(message: String, val userMessage: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : ApiError("API URL or key not set", "Enter the API URL and access key first")

    class Http(val status: Int, val detail: String) : ApiError(
        if (detail.isEmpty()) "HTTP $status" else "HTTP $status: $detail",
        when (status) {
            401, 403 -> "Access key rejected (HTTP $status) — check the dashboard access key"
            else -> "Server error (HTTP $status)"
        },
    )

    class Network(cause: Throwable) : ApiError(
        cause.message ?: "Network error",
        "Can't reach the server — check the URL and your connection",
        cause,
    )

    class Decoding : ApiError("Unexpected response", "Unexpected response — is this the Net Worth API URL?")

    class Storage(cause: Throwable) : ApiError(
        cause.message ?: "Storage error",
        "Couldn't save data on this device — is storage full?",
        cause,
    )
}

/** True when the server said the key is wrong (as opposed to being down or unreachable). */
val ApiError.rejectsKey: Boolean get() = this is ApiError.Http && (status == 401 || status == 403)

/** What [Sync] needs from the network; lets tests substitute a fake. */
interface HistorySource {
    suspend fun history(): HistoryResponse
}

/**
 * Talks to net-worth-service. Deliberately has no logging interceptor: the
 * X-Api-Key header must never reach a log.
 */
class ApiClient(
    private val baseUrl: HttpUrl,
    apiKey: String,
    private val http: OkHttpClient = DEFAULT_HTTP,
) : HistorySource {
    private val key = apiKey.trim()

    override suspend fun history(): HistoryResponse {
        val url = baseUrl.newBuilder().addPathSegments("api/v1/history").build()
        return call(request(url)) { decode(it) }
    }

    private fun request(url: HttpUrl): Request {
        val b = Request.Builder().url(url).get()
        try {
            b.header("X-Api-Key", key)
        } catch (e: IllegalArgumentException) {          // control characters in a pasted key
            throw ApiError.NotConfigured()
        }
        return b.build()
    }

    private suspend fun <T> call(req: Request, parse: (Response) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiError.Http(resp.code, errorDetail(resp))
                parse(resp)
            }
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
    }

    private fun decode(resp: Response): HistoryResponse =
        try {
            ApiJson.decodeFromString<HistoryResponse>(resp.body?.string().orEmpty())
        } catch (e: SerializationException) {
            throw ApiError.Decoding()
        } catch (e: IllegalArgumentException) {
            throw ApiError.Decoding()
        }

    /** The auth filter answers `{"error": ...}`; Spring's default error body uses `message`. */
    private fun errorDetail(resp: Response): String = runCatching {
        val obj = Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
        (obj["message"] ?: obj["error"])?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")

    companion object {
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
