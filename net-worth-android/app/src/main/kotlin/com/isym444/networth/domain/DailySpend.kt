package com.isym444.networth.domain

import com.isym444.networth.data.Snapshot
import kotlin.math.max

/**
 * "Max Daily Spend" maths, like `dailySpend` / `dailySpendToInvestBuffer` (dashboard.component.ts) except that the widget
 * does not subtract the monthly subscriptions: what is left on Chase after Amex, spread over the days to the next payday.
 */
object DailySpend {
    /** Reserved to invest at month end in the "buffer" variant. */
    const val INVEST_BUFFER = 400.0

    /** null when the snapshot has no Chase/Amex figures (history from before those columns existed). */
    fun perDay(snapshot: Snapshot, days: Int, buffer: Double = 0.0): Double? {
        val chase = snapshot.chaseTotal ?: return null
        val amex = snapshot.amexDebtTotal ?: return null
        return (chase - amex - buffer) / max(1, days)
    }
}
