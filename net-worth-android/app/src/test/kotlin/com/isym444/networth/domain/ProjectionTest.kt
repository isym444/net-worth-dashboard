package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProjectionTest {
    private val today = LocalDate.of(2026, 9, 30)

    private fun reach(current: Double, target: Double, contribution: Double) =
        Projection.reach(current, target, contribution, today)

    @Test fun matchesDashboardWithoutContributions() {
        assertEquals(Reach.In(123, 40), reach(50_000.0, 100_000.0, 0.0))
        assertEquals("10年3か月 (40歳)", reach(50_000.0, 100_000.0, 0.0).text())
        assertEquals("4年7か月 (34歳)", reach(184_320.0, 250_000.0, 0.0).text())
        assertEquals("25年0か月 (55歳)", reach(184_320.0, 1_000_000.0, 0.0).text())
    }

    @Test fun matchesDashboardWithContributions() {
        assertEquals(Reach.In(36, 33), reach(50_000.0, 100_000.0, 1_000.0))
        assertEquals("3年0か月 (33歳)", reach(50_000.0, 100_000.0, 1_000.0).text())
        assertEquals("2年6か月 (32歳)", reach(184_320.0, 250_000.0, 1_000.0).text())
        assertEquals("15年4か月 (45歳)", reach(184_320.0, 1_000_000.0, 1_500.0).text())
    }

    @Test fun alreadyReachedIncludesEqualAndAbove() {
        assertEquals(Reach.Reached(30), reach(100_000.0, 100_000.0, 0.0))
        assertEquals("達成 (30歳)", reach(250_000.0, 100_000.0, 0.0).text())
    }

    @Test fun justUnderTheTargetTakesOneMonth() {
        assertEquals("0年1か月 (30歳)", reach(99_999.99, 100_000.0, 0.0).text())
    }

    @Test fun neverWhenNotReachableWithinTheCap() {
        assertEquals(Reach.Never, reach(1_000.0, 1_000_000.0, 0.0))
        assertEquals("100年以上", reach(1_000.0, 1_000_000.0, 0.0).text())
        assertEquals("100年以上", reach(0.0, 100_000.0, 0.0).text())
        assertNull(Projection.monthsToReach(0.0, 100_000.0, 0.0))
    }

    @Test fun negativeNetWorthWithNoContributionNeverReaches() {
        assertEquals(Reach.Never, reach(-5_000.0, 100_000.0, 0.0))
    }

    @Test fun negativeNetWorthWithAContributionEventuallyReaches() {
        val r = reach(-5_000.0, 100_000.0, 1_000.0)
        assertTrue("expected In, was $r", r is Reach.In)
    }

    @Test fun monthsToReachIsZeroWhenAlreadyThere() {
        assertEquals(0, Projection.monthsToReach(100_000.0, 100_000.0, 0.0))
    }

    @Test fun ageRollsOverOnTheBirthday() {
        // Birthday is 14 June: the day before is still 29, the day itself is 30.
        assertEquals(29, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 0))
        assertEquals(30, Projection.ageAfterMonths(LocalDate.of(2026, 6, 14), 0))
        assertEquals(30, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 1))    // 13 Jul 2026
        assertEquals(30, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 12))   // 13 Jun 2027, day before the 31st birthday
        assertEquals(31, Projection.ageAfterMonths(LocalDate.of(2026, 6, 13), 13))   // 13 Jul 2027
    }

    @Test fun monthEndClampDoesNotChangeTheAge() {
        // JS setMonth overflows (31 Jan + 1 month = 3 Mar); java.time clamps to 28 Feb. Both are age 29.
        assertEquals(29, Projection.ageAfterMonths(LocalDate.of(2026, 1, 31), 1))
    }

    @Test fun milestoneListMatchesTheDashboard() {
        assertEquals(listOf(100_000.0, 250_000.0, 500_000.0, 750_000.0, 1_000_000.0), ProjectionConfig.MILESTONES)
    }
}
