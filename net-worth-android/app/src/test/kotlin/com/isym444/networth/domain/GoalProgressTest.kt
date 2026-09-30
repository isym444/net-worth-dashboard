package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoalProgressTest {
    @Test fun matchesTheWorkedExample() {
        val g = GoalProgress.compute(48_000.0, 100_000.0, 1_000.0, 0.07)
        assertEquals(36.9, g.monthsRemaining!!, 0.05)
        assertEquals(0.591, g.growthShare, 0.001)         // money progress would be 48.0%
        assertEquals(0.536, g.timeShare!!, 0.001)
    }

    @Test fun alreadyReachedIsEverythingAt100Percent() =
        assertEquals(GoalProgress(0.0, 1.0, 1.0), GoalProgress.compute(120_000.0, 100_000.0, 1_000.0, 0.07))

    @Test fun withoutContributionsItIsAllYourExistingMoneyAndTimeProgressIsUndefined() {
        val g = GoalProgress.compute(48_000.0, 100_000.0, 0.0, 0.07)
        assertEquals(1.0, g.growthShare, 1e-9)
        assertNull(g.timeShare)
        assertEquals(Math.log(100_000.0 / 48_000.0) / Math.log(1.07) * 12, g.monthsRemaining!!, 0.01)
    }

    @Test fun withoutReturnItIsMoneyProgressAndStraightLineMonths() {
        val g = GoalProgress.compute(40_000.0, 100_000.0, 1_000.0, 0.0)
        assertEquals(60.0, g.monthsRemaining!!, 1e-9)
        assertEquals(0.4, g.growthShare, 1e-9)
        assertEquals(0.4, g.timeShare!!, 1e-9)
    }

    @Test fun noReturnAndNoContributionsCanNeverReachIt() {
        val g = GoalProgress.compute(40_000.0, 100_000.0, 0.0, 0.0)
        assertNull(g.monthsRemaining)
        assertNull(g.timeShare)
    }

    @Test fun startingFromNothingIsZeroTimeProgressAndZeroGrowthShare() {
        val g = GoalProgress.compute(0.0, 100_000.0, 1_000.0, 0.07)
        assertEquals(0.0, g.timeShare!!, 1e-9)
        assertEquals(0.0, g.growthShare, 1e-9)
        assertNotNull(g.monthsRemaining)
    }

    @Test fun debtBeyondWhatContributionsCanCatchUpIsUnreachable() {
        val g = GoalProgress.compute(-500_000.0, 100_000.0, 1_000.0, 0.07)
        assertNull(g.monthsRemaining)
        assertEquals(0.0, g.growthShare, 0.0)
    }

    @Test fun bothMeasuresGrowAsTheSavingsGrow() {
        val runs = listOf(5_000.0, 20_000.0, 50_000.0, 90_000.0).map { GoalProgress.compute(it, 100_000.0, 1_000.0, 0.07) }
        assertEquals(runs.map { it.growthShare }.sorted(), runs.map { it.growthShare })
        assertEquals(runs.map { it.timeShare!! }.sorted(), runs.map { it.timeShare!! })
    }
}
