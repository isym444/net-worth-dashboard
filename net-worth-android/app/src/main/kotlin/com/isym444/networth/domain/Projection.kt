package com.isym444.networth.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

sealed interface Reach {
    data class Reached(val age: Int) : Reach
    data class In(val months: Int, val age: Int) : Reach
    data object Never : Reach

    /** Japanese wording: "達成 (30歳)", "4年7か月 (34歳)", "100年以上". */
    fun text(): String = when (this) {
        is Reached -> "達成 (${age}歳)"
        is In -> "${months / 12}年${months % 12}か月 (${age}歳)"
        Never -> "100年以上"
    }
}

/** Pure port of `timeToReachPortfolio` (dashboard.component.ts). */
object Projection {
    /** The net worth at which one month of growth equals the monthly contribution (`c / r`; port of `contributionBreakevenNetWorth`). */
    fun breakevenNetWorth(monthlyContribution: Double): Double =
        monthlyContribution / ((1 + ProjectionConfig.ANNUAL_RETURN).pow(1.0 / 12) - 1)

    /** 0 if `current >= target`; null if not reached within [maxMonths] (default [ProjectionConfig.MAX_MONTHS]). */
    fun monthsToReach(current: Double, target: Double, monthlyContribution: Double, maxMonths: Int = ProjectionConfig.MAX_MONTHS): Int? {
        if (current >= target) return 0
        val growth = (1 + ProjectionConfig.ANNUAL_RETURN).pow(1.0 / 12)
        var balance = current
        var months = 0
        while (balance < target && months < maxMonths) {
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
