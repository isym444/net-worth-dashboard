package com.isym444.networth.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One JSON file holding the last good API response. Writes are atomic (temp file + rename) so the
 * widget never reads a half-written cache, and a missing or corrupt file loads as empty instead of throwing.
 */
class HistoryCache(private val file: File) {
    fun load(): CacheSnapshot =
        runCatching { ApiJson.decodeFromString<CacheSnapshot>(file.readText()) }.getOrDefault(CacheSnapshot())

    fun save(snapshot: CacheSnapshot) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("cache file has no parent directory")
        dir.mkdirs()
        // A unique temp file per call: the hourly worker and "Save & test" can save at the same moment.
        val tmp = File.createTempFile("cache", ".tmp", dir)
        try {
            tmp.writeText(ApiJson.encodeToString(CacheSnapshot.serializer(), snapshot))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()        // no-op after a successful move
        }
    }

    companion object {
        fun forContext(context: Context) = HistoryCache(File(context.filesDir, "cache.json"))
    }
}
