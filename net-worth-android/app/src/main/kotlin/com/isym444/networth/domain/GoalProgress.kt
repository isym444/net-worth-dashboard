package com.isym444.networth.domain

import kotlin.math.ln
import kotlin.math.pow

/**
 * How far along the way to a target you are, in two ways (both on the same constant-return assumption as the milestones):
 *  - [growthShare]: the share of the target that will have come from the money you already hold, once it has grown until
 *    the target is reached: `P(1+r)^n / T`.
 *  - [timeShare]: the share of the whole journey from zero (same monthly contribution and return) that is already behind
 *    you: `(n0 - n) / n0`. Null when it is undefined (no monthly contribution).
 * [monthsRemaining] is `n`, the (fractional) months still to go; null when the target can never be reached.
 */
data class GoalProgress(val monthsRemaining: Double?, val growthShare: Double, val timeShare: Double?) {
    companion object {
        /** [p] current savings, [t] target, [c] monthly contribution (end of month), [annualReturn] as a decimal. */
        fun compute(p: Double, t: Double, c: Double, annualReturn: Double): GoalProgress {
            if (p >= t) return GoalProgress(0.0, 1.0, 1.0)
            val r = (1 + annualReturn).pow(1.0 / 12) - 1

            if (r <= 0.0) {                                              // no growth: contributions are the only driver
                if (c <= 0.0) return GoalProgress(null, (p / t).coerceIn(0.0, 1.0), null)
                val share = (p / t).coerceIn(0.0, 1.0)
                return GoalProgress((t - p) / c, share, share)
            }

            val logGrowth = ln(1 + r)
            if (c <= 0.0) {                                              // no contributions: it is all your existing money
                if (p <= 0.0) return GoalProgress(null, 0.0, null)
                return GoalProgress(ln(t / p) / logGrowth, 1.0, null)    // time progress is undefined: you can never start from zero
            }

            val k = c / r
            if (p + k <= 0.0) return GoalProgress(null, 0.0, 0.0)        // so far in debt that contributions never catch up
            val n = ln((t + k) / (p + k)) / logGrowth
            val n0 = ln(1 + t * r / c) / logGrowth
            val growth = if (p > 0.0) (p * (1 + r).pow(n) / t).coerceIn(0.0, 1.0) else 0.0
            val time = ((n0 - n) / n0).coerceIn(0.0, 1.0)
            return GoalProgress(n, growth, time)
        }
    }
}
