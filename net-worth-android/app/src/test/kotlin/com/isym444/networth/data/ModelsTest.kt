package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    // Shape of the real /api/v1/history response, including fields the widget ignores.
    private val realistic = """
        {"snapshots":[
          {"id":1,"timestamp":"2026-09-28T07:00:00Z","currency":"GBP","t212Total":100.5,"cryptoTotal":1.0,
           "chaseTotal":null,"amexDebtTotal":null,"amountLeft":null,"grandTotal":183000.5,
           "grandTotalEur":null,"aiCommentary":"Spending is up"},
          {"id":2,"timestamp":"2026-09-29T07:00:00Z","currency":"GBP","grandTotal":184320.25,
           "someFutureField":{"a":1}}],
         "expectedNetWorthAt40":900000.0,"expectedNetWorthAt50":1500000.0,
         "expectedNetWorthAt60":2500000.0,"expectedNetWorthAt65":3500000.0,
         "expectedNetWorthWithContributionsAt40":1000000.0,"expectedNetWorthWithContributionsAt50":1700000.0,
         "expectedNetWorthWithContributionsAt60":2900000.0,"expectedNetWorthWithContributionsAt65":4100000.0,
         "estimatedNetWorth1y":200000.0,"monthlyContribution":1000.0}
    """.trimIndent()

    @Test fun decodesRealisticResponseAndIgnoresUnknownKeys() {
        val h = ApiJson.decodeFromString<HistoryResponse>(realistic)
        assertEquals(2, h.snapshots.size)
        assertEquals(184320.25, h.snapshots.last().grandTotal, 0.0)
        assertEquals("GBP", h.snapshots.last().currency)
        assertEquals("2026-09-29T07:00:00Z", h.snapshots.last().timestamp)
        assertEquals(900000.0, h.expectedNetWorthAt40!!, 0.0)
        assertEquals(4100000.0, h.expectedNetWorthWithContributionsAt65!!, 0.0)
        assertEquals(200000.0, h.estimatedNetWorth1y!!, 0.0)
        assertEquals(1000.0, h.monthlyContribution!!, 0.0)
    }

    @Test fun nullOrMissingProjectionFieldsDecodeAsNull() {
        val h = ApiJson.decodeFromString<HistoryResponse>(
            """{"snapshots":[{"grandTotal":1.0}],"estimatedNetWorth1y":null,"monthlyContribution":null}""",
        )
        assertNull(h.expectedNetWorthAt40)
        assertNull(h.expectedNetWorthWithContributionsAt65)
        assertNull(h.estimatedNetWorth1y)
        assertNull(h.monthlyContribution)
        assertEquals("GBP", h.snapshots.single().currency)   // missing currency falls back to the default
    }

    @Test fun brandNewDatabaseDecodes() {
        assertTrue(ApiJson.decodeFromString<HistoryResponse>("""{"snapshots":[]}""").snapshots.isEmpty())
        assertTrue(ApiJson.decodeFromString<HistoryResponse>("{}").snapshots.isEmpty())
    }

    @Test fun cacheSnapshotRoundTrips() {
        val original = CacheSnapshot(
            fetchedAt = 1_790_000_000_000,
            history = HistoryResponse(
                snapshots = listOf(Snapshot(id = 2, timestamp = "t", currency = "EUR", grandTotal = 5.5)),
                estimatedNetWorth1y = 9.0,
            ),
        )
        val json = ApiJson.encodeToString(CacheSnapshot.serializer(), original)
        assertEquals(original, ApiJson.decodeFromString<CacheSnapshot>(json))
    }

    @Test fun emptyCacheHasNoHistory() {
        val c = CacheSnapshot()
        assertEquals(0L, c.fetchedAt)
        assertNull(c.history)
    }
}
