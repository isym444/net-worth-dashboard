package com.isym444.networth.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lenient on input (unknown keys, null for a defaulted field -> default), complete on output. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** The few fields the widget reads from a `NetWorthSnapshot`; everything else is ignored on decode. */
@Serializable
data class Snapshot(
    val id: Long = 0,
    val timestamp: String = "",
    val currency: String = "GBP",
    val grandTotal: Double = 0.0,
    val chaseTotal: Double? = null,                       // null on old snapshots, which predate the column
    val amexDebtTotal: Double? = null,
)

/** Mirrors `NetWorthHistoryResponse` (net-worth-service). Every projection is nullable. */
@Serializable
data class HistoryResponse(
    val snapshots: List<Snapshot>,                        // oldest first; required so a non-history JSON body fails to decode
    val expectedNetWorthAt40: Double? = null,
    val expectedNetWorthAt50: Double? = null,
    val expectedNetWorthAt60: Double? = null,
    val expectedNetWorthAt65: Double? = null,
    val expectedNetWorthWithContributionsAt40: Double? = null,
    val expectedNetWorthWithContributionsAt50: Double? = null,
    val expectedNetWorthWithContributionsAt60: Double? = null,
    val expectedNetWorthWithContributionsAt65: Double? = null,
    val estimatedNetWorth1y: Double? = null,
    val monthlyContribution: Double? = null,
    val peakNetWorth: Double? = null,                     // highest total over ALL snapshots; `snapshots` is thinned by the server
)

/** What the widget renders from; persisted by HistoryCache. `fetchedAt` is epoch milliseconds (0 = never). */
@Serializable
data class CacheSnapshot(
    val fetchedAt: Long = 0,
    val history: HistoryResponse? = null,
)
