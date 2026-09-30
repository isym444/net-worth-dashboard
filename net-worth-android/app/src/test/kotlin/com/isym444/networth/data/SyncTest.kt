package com.isym444.networth.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache() = HistoryCache(File(tmp.root, "cache.json"))

    private fun source(block: () -> HistoryResponse) = object : HistorySource {
        override suspend fun history() = block()
    }

    private fun history(vararg totals: Double) = HistoryResponse(
        snapshots = totals.mapIndexed { i, t -> Snapshot(id = i.toLong(), grandTotal = t) },
        estimatedNetWorth1y = 9.0,
        monthlyContribution = 1000.0,
    )

    @Test fun successKeepsOnlyTheLastTwoSnapshotsAndStampsFetchTime() {
        val cache = cache()
        val results = mutableListOf<Pair<SyncResult, Long>>()
        val sync = Sync(cache, { source { history(1.0, 2.0, 3.0, 4.0) } }, clock = { 777L }, onResult = { r, t -> results += r to t })

        assertEquals(SyncResult.Updated, runBlocking { sync.refresh() })

        val saved = cache.load()
        assertEquals(777L, saved.fetchedAt)
        assertEquals(listOf(3.0, 4.0), saved.history!!.snapshots.map { it.grandTotal })
        assertEquals(9.0, saved.history!!.estimatedNetWorth1y!!, 0.0)          // projections are preserved
        assertEquals(listOf<Pair<SyncResult, Long>>(SyncResult.Updated to 777L), results)
    }

    @Test fun anEmptyResponseReplacesTheCache() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        runBlocking { Sync(cache, { source { history() } }, clock = { 2L }).refresh() }
        assertTrue(cache.load().history!!.snapshots.isEmpty())
        assertEquals(2L, cache.load().fetchedAt)
    }

    @Test fun aRejectedKeyLeavesTheCacheExactlyAsItWas() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        val before = File(tmp.root, "cache.json").readText()
        val result = runBlocking { Sync(cache, { source { throw ApiError.Http(401, "no") } }).refresh() }
        assertTrue(result is SyncResult.Failed && result.error.rejectsKey)
        assertEquals(before, File(tmp.root, "cache.json").readText())
    }

    @Test fun aNetworkFailureLeavesTheCacheAlone() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        val result = runBlocking { Sync(cache, { source { throw ApiError.Network(java.io.IOException("offline")) } }).refresh() }
        assertTrue(result is SyncResult.Failed)
        assertEquals(1L, cache.load().fetchedAt)
    }

    @Test fun aDecodingFailureLeavesTheCacheAlone() {
        val cache = cache().also { it.save(CacheSnapshot(fetchedAt = 1, history = history(5.0))) }
        runBlocking { Sync(cache, { source { throw ApiError.Decoding() } }).refresh() }
        assertEquals(5.0, cache.load().history!!.snapshots.single().grandTotal, 0.0)
    }

    @Test fun noSourceMeansNotConfigured() {
        val cache = cache()
        val result = runBlocking { Sync(cache, { null }).refresh() }
        assertTrue(result is SyncResult.Failed && result.error is ApiError.NotConfigured)
        assertNull(cache.load().history)
    }

    @Test fun aStorageFailureIsReportedNotThrown() {
        val blocker = tmp.newFile("blocker")                       // a file where a directory is needed
        val broken = HistoryCache(File(blocker, "cache.json"))
        val result = runBlocking { Sync(broken, { source { history(1.0) } }).refresh() }
        assertTrue(result is SyncResult.Failed && result.error is ApiError.Storage)
    }
}
