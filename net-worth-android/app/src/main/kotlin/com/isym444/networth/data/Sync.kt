package com.isym444.networth.data

import java.io.IOException

sealed interface SyncResult {
    data object Updated : SyncResult
    data class Failed(val error: ApiError) : SyncResult
}

/**
 * Refreshes the cache from the API. On *any* failure the cache is left exactly as it was,
 * so the widget keeps rendering the last good data.
 */
class Sync(
    private val cache: HistoryCache,
    private val source: () -> HistorySource?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onResult: (SyncResult, Long) -> Unit = { _, _ -> },
) {
    suspend fun refresh(): SyncResult {
        val result = attempt()
        onResult(result, clock())
        return result
    }

    private suspend fun attempt(): SyncResult {
        val api = source() ?: return SyncResult.Failed(ApiError.NotConfigured())
        return try {
            val fresh = api.history()
            // Keep every snapshot /history returns: the change lines and all-time high need the whole history.
            // It is small (the API thins old data, and `Snapshot` decodes only six fields).
            cache.save(CacheSnapshot(fetchedAt = clock(), history = fresh))
            SyncResult.Updated
        } catch (e: ApiError) {
            SyncResult.Failed(e)
        } catch (e: IOException) {          // only the cache write can throw this (ApiClient maps its own I/O errors)
            SyncResult.Failed(ApiError.Storage(e))
        }
    }
}
