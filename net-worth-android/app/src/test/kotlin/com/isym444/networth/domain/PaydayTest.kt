package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PaydayTest {
    private fun next(y: Int, m: Int, d: Int) = Payday.nextAfter(LocalDate.of(y, m, d))
    private fun days(y: Int, m: Int, d: Int) = Payday.daysUntilNext(LocalDate.of(y, m, d))

    @Test fun bankHolidays2026() {
        assertEquals(
            setOf(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 4, 6), LocalDate.of(2026, 5, 4),
                LocalDate.of(2026, 5, 25), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 28),
            ),
            Payday.bankHolidays(2026).toSet(),
        )
    }

    @Test fun christmasOnASaturdayDoubleShifts() {
        // 2021: 25th is Saturday, 26th Sunday -> Mon 27th and Tue 28th.
        val h = Payday.bankHolidays(2021).toSet()
        assertTrue(LocalDate.of(2021, 12, 27) in h && LocalDate.of(2021, 12, 28) in h)
    }

    @Test fun nextPaydayThisMonthWhenTheTwentyThirdIsAWorkingDay() {
        assertEquals(LocalDate.of(2026, 10, 23), next(2026, 10, 1))      // Friday
        assertEquals(23, days(2026, 9, 30))
    }

    @Test fun aWeekendPaydayMovesBackToFriday() {
        assertEquals(LocalDate.of(2026, 8, 21), next(2026, 8, 1))        // 23rd is a Sunday
        assertEquals(LocalDate.of(2027, 1, 22), next(2027, 1, 1))        // 23rd is a Saturday
    }

    @Test fun onPaydayItselfTheNextOneIsNextMonth() {
        assertEquals(LocalDate.of(2026, 11, 23), next(2026, 10, 23))
        assertEquals(31, days(2026, 10, 23))
    }

    @Test fun afterPaydayRollsToNextMonthAndYear() {
        assertEquals(LocalDate.of(2026, 11, 23), next(2026, 10, 24))
        assertEquals(LocalDate.of(2027, 1, 22), next(2026, 12, 24))
    }

    @Test fun theDayBeforePaydayIsOneDay() = assertEquals(1, days(2026, 10, 22))
}
