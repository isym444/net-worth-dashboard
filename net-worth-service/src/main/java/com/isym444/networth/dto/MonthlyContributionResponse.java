package com.isym444.networth.dto;

public record MonthlyContributionResponse(
        Double monthlyContribution,
        Double estimatedNetWorth1y,
        Double expectedNetWorthWithContributionsAt40,
        Double expectedNetWorthWithContributionsAt50,
        Double expectedNetWorthWithContributionsAt60,
        Double expectedNetWorthWithContributionsAt65) {
}
