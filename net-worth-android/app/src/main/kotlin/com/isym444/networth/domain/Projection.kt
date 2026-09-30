package com.isym444.networth.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

sealed interface Reach {
    data class Reached(val age: Int) : Reach
    data class In(val months: Int, val age: Int) : Reach
    data object Never : Reach

    /** Same wording as the dashboard: "Reached (age 30)", "4y 7m (age 34)", "100y+". */
    fun text(): String = when (this) {
        is Reached -> "Reached (age $age)"
        is In -> "${months / 12}y ${months % 12}m (age $age)"
        Never -> "100y+"
    }
}

/** Pure port of `timeToReachPortfolio` (dashboard.component.ts). */
object Projection {
    /** 0 if `current >= target`; null if not reached within [ProjectionConfig.MAX_MONTHS]. */
    fun monthsToReach(current: Double, target: Double, monthlyContribution: Double): Int? {
        if (current >= target) return 0
        val growth = (1 + ProjectionConfig.ANNUAL_RETURN).pow(1.0 / 12)
        var balance = current
        var months = 0
        while (balance < target && months < ProjectionConfig.MAX_MONTHS) {
            balance = balance * growth + monthlyContribution
            months++
        }
        return if (balance >= target) months else null
    }

    fun reach(current: Double, target: Double, monthlyContribution: Double, today: LocalDate): Reach {
        val months = monthsToReach(current, target, monthlyContribution) ?: return Reach.Never
        val age = ageAfterMonths(today, months)
        return if (months == 0) Reach.Reached(age) else Reach.In(months, age)
    }

    fun ageAfterMonths(today: LocalDate, months: Int): Int =
        ChronoUnit.YEARS.between(ProjectionConfig.BIRTH_DATE, today.plusMonths(months.toLong())).toInt()
}
