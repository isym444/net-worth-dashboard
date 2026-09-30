package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun snap(total: Double) = CacheSnapshot(
        fetchedAt = 42,
        history = HistoryResponse(snapshots = listOf(Snapshot(grandTotal = total))),
    )

    @Test fun missingFileLoadsAsEmpty() {
        val c = HistoryCache(File(tmp.root, "nope.json")).load()
        assertEquals(0L, c.fetchedAt)
        assertNull(c.history)
    }

    @Test fun corruptFileLoadsAsEmptyInsteadOfThrowing() {
        val f = tmp.newFile("cache.json").apply { writeText("{ this is not json") }
        assertNull(HistoryCache(f).load().history)
    }

    @Test fun roundTrips() {
        val cache = HistoryCache(File(tmp.root, "cache.json"))
        cache.save(snap(123.0))
        assertEquals(snap(123.0), cache.load())
    }

    @Test fun saveOverwritesAndLeavesNoTempFiles() {
        val cache = HistoryCache(File(tmp.root, "cache.json"))
        cache.save(snap(1.0))
        cache.save(snap(2.0))
        assertEquals(2.0, cache.load().history!!.snapshots.single().grandTotal, 0.0)
        assertEquals(listOf("cache.json"), tmp.root.list()!!.sorted())
    }

    @Test fun createsMissingParentDirectories() {
        val cache = HistoryCache(File(tmp.root, "a/b/cache.json"))
        cache.save(snap(5.0))
        assertTrue(File(tmp.root, "a/b/cache.json").exists())
    }
}
