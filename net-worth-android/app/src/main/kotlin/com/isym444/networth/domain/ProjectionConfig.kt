package com.isym444.networth.domain

import java.time.LocalDate

/**
 * Projection assumptions. These duplicate values that also live elsewhere; change all copies together:
 *  - net-worth-frontend/src/app/dashboard.component.ts: assumedAnnualReturn, netWorthMilestones, birthDate, maxProjectionMonths
 *  - net-worth-service PortfolioProjectionService.ANNUAL_GROWTH_RATE and NetWorthHistoryService.BIRTH_DATE
 */
object ProjectionConfig {
    const val ANNUAL_RETURN = 0.07
    const val MAX_MONTHS = 1200
    val MILESTONES: List<Double> = listOf(100_000.0, 250_000.0, 500_000.0, 750_000.0, 1_000_000.0)
    val BIRTH_DATE: LocalDate = LocalDate.of(1996, 6, 14)
}
