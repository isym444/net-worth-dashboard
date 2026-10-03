package com.isym444.networth.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

/**
 * Paycheck-date maths, ported from `net-worth-frontend/src/app/bank-holidays.ts` (keep the two in step; the macOS app has a copy too).
 * English bank holidays are computed locally (including the Easter-based ones); no I/O.
 */
object Payday {
    const val DAY_OF_MONTH = 23

    /**
     * The next payday strictly after [today]: the 23rd, or the nearest working day before it when that is a weekend or
     * bank holiday. On payday itself this is next month's, so "days left" never collapses to 0.
     */
    fun nextAfter(today: LocalDate): LocalDate {
        val thisMonth = adjusted(today.withDayOfMonth(1))
        return if (thisMonth.isAfter(today)) thisMonth else adjusted(today.withDayOfMonth(1).plusMonths(1))
    }

    /** Calendar days from [today] to the next payday (payday tomorrow = 1). Floored at 1, like the dashboard. */
    fun daysUntilNext(today: LocalDate): Int = max(1, ChronoUnit.DAYS.between(today, nextAfter(today)).toInt())

    /**
     * English bank holidays for [year], with weekend substitution: a holiday on a weekend moves to the next free weekday,
     * skipping dates already taken (which also yields the Christmas/Boxing Day double shift).
     */
    fun bankHolidays(year: Int): List<LocalDate> {
        val easter = easterSunday(year)
        val raw = listOf(
            LocalDate.of(year, 1, 1), easter.minusDays(2), easter.plusDays(1),
            LocalDate.of(year, 5, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)),
            LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)),
            LocalDate.of(year, 8, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)),
            LocalDate.of(year, 12, 25), LocalDate.of(year, 12, 26),
        )
        val used = mutableSetOf<LocalDate>()
        return raw.map { d ->
            var candidate = skipWeekend(d)
            while (candidate in used) candidate = skipWeekend(candidate.plusDays(1))
            used += candidate
            candidate
        }
    }

    private fun adjusted(firstOfMonth: LocalDate): LocalDate {
        var candidate = firstOfMonth.withDayOfMonth(DAY_OF_MONTH)
        val holidays = bankHolidays(candidate.year).toSet()
        while (isWeekend(candidate) || candidate in holidays) candidate = candidate.minusDays(1)
        return candidate
    }

    private fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    private fun skipWeekend(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.plusDays(2)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    /** Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val monthDay = h + l - 7 * m + 114
        return LocalDate.of(year, monthDay / 31, monthDay % 31 + 1)
    }
}
