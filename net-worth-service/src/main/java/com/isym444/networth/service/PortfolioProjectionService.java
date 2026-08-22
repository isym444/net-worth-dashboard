package com.isym444.networth.service;

import com.isym444.networth.model.PortfolioSettings;
import com.isym444.networth.repository.PortfolioSettingsRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Projects net worth one year out: the current grand total plus twelve
 * monthly contributions, each compounding at {@link #ANNUAL_GROWTH_RATE}
 * for however much of the year remains after it's deposited. The monthly
 * contribution amount is a single user-adjustable setting (not per-currency),
 * matching the single-currency assumption the rest of this app already makes
 * (see NetWorthController/NetWorthHistoryService's resolveCurrency).
 */
@Service
public class PortfolioProjectionService {

    private static final double ANNUAL_GROWTH_RATE = 0.07;
    private static final int CONTRIBUTION_MONTHS = 12;

    private final PortfolioSettingsRepository settingsRepository;

    public PortfolioProjectionService(PortfolioSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public double getMonthlyContribution() {
        return settingsRepository.findById(PortfolioSettings.SINGLETON_ID)
                .map(PortfolioSettings::getMonthlyContribution)
                .orElse(0.0);
    }

    /**
     * Persists the new monthly contribution default. Every net worth estimate
     * that depends on it (1-year, and net worth at 40/50/60/65 with
     * contributions) is computed fresh on every /history call from this
     * setting plus the latest snapshot's grand total — nothing derived from
     * it is stored, so there's nothing here to keep in sync.
     */
    public double updateMonthlyContribution(double monthlyContribution) {
        if (monthlyContribution < 0) {
            throw new IllegalArgumentException("monthlyContribution must not be negative");
        }

        PortfolioSettings settings = settingsRepository.findById(PortfolioSettings.SINGLETON_ID)
                .orElseGet(() -> {
                    PortfolioSettings created = new PortfolioSettings();
                    created.setId(PortfolioSettings.SINGLETON_ID);
                    return created;
                });
        settings.setMonthlyContribution(monthlyContribution);
        settings.setUpdatedAt(Instant.now());
        settingsRepository.save(settings);

        return monthlyContribution;
    }

    /**
     * Compounds the current grand total for a full year, plus twelve monthly
     * contributions. Contribution index 0 is deposited "now" and compounds
     * for the full year; index 11 is deposited eleven months from now and
     * compounds for only one twelfth of the year.
     */
    double calculateEstimatedNetWorth1y(double grandTotal, double monthlyContribution) {
        return calculateEstimatedNetWorthWithContributions(grandTotal, monthlyContribution, 1.0);
    }

    /**
     * Same idea as {@link #calculateEstimatedNetWorth1y}, generalized to any
     * horizon: the grand total compounds for {@code years}, and it's joined by
     * one monthly contribution per month up to {@code round(years * 12)} of
     * them, each compounding from its deposit date to the end of the horizon.
     */
    double calculateEstimatedNetWorthWithContributions(double grandTotal, double monthlyContribution, double years) {
        double effectiveYears = Math.max(0, years);
        double lumpSumFutureValue = grandTotal * Math.pow(1 + ANNUAL_GROWTH_RATE, effectiveYears);

        long totalMonths = Math.round(effectiveYears * CONTRIBUTION_MONTHS);
        double contributionsFutureValue = 0;
        for (long monthIndex = 0; monthIndex < totalMonths; monthIndex++) {
            long monthsOfGrowth = totalMonths - monthIndex;
            contributionsFutureValue += monthlyContribution
                    * Math.pow(1 + ANNUAL_GROWTH_RATE, monthsOfGrowth / (double) CONTRIBUTION_MONTHS);
        }

        return round2(lumpSumFutureValue + contributionsFutureValue);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
