package com.isym444.networth.service;

import com.isym444.networth.dto.MonthlyContributionResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.model.PortfolioSettings;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.repository.PortfolioSettingsRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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
    private final NetWorthSnapshotRepository snapshotRepository;

    public PortfolioProjectionService(PortfolioSettingsRepository settingsRepository,
                                       NetWorthSnapshotRepository snapshotRepository) {
        this.settingsRepository = settingsRepository;
        this.snapshotRepository = snapshotRepository;
    }

    public double getMonthlyContribution() {
        return settingsRepository.findById(PortfolioSettings.SINGLETON_ID)
                .map(PortfolioSettings::getMonthlyContribution)
                .orElse(0.0);
    }

    /**
     * Persists the new monthly contribution default, then recomputes and
     * saves {@code estimatedNetWorth1y} onto the latest snapshot row for the
     * resolved currency (using that row's already-known grand total, no
     * external API calls) so the dashboard's estimate reflects the change
     * immediately, without waiting for the next snapshot.
     */
    public MonthlyContributionResponse updateMonthlyContribution(double monthlyContribution, String currency) {
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

        Double estimate = null;
        String resolved = resolveCurrency(currency);
        if (resolved != null) {
            NetWorthSnapshot latest = snapshotRepository
                    .findFirstByCurrencyOrderByTimestampDescIdDesc(resolved)
                    .orElse(null);
            if (latest != null) {
                estimate = calculateEstimatedNetWorth1y(latest.getGrandTotal(), monthlyContribution);
                latest.setEstimatedNetWorth1y(estimate);
                snapshotRepository.save(latest);
            }
        }

        return new MonthlyContributionResponse(monthlyContribution, estimate);
    }

    /**
     * Compounds the current grand total for a full year, plus twelve monthly
     * contributions. Contribution index 0 is deposited "now" and compounds
     * for the full year; index 11 is deposited eleven months from now and
     * compounds for only one twelfth of the year.
     */
    double calculateEstimatedNetWorth1y(double grandTotal, double monthlyContribution) {
        double lumpSumFutureValue = grandTotal * (1 + ANNUAL_GROWTH_RATE);

        double contributionsFutureValue = 0;
        for (int monthIndex = 0; monthIndex < CONTRIBUTION_MONTHS; monthIndex++) {
            double monthsOfGrowth = CONTRIBUTION_MONTHS - monthIndex;
            contributionsFutureValue += monthlyContribution
                    * Math.pow(1 + ANNUAL_GROWTH_RATE, monthsOfGrowth / (double) CONTRIBUTION_MONTHS);
        }

        return round2(lumpSumFutureValue + contributionsFutureValue);
    }

    // Same fallback NetWorthController.resolveCurrency/NetWorthHistoryService.resolveCurrency use:
    // whichever currency sorts first alphabetically among logged snapshots.
    private String resolveCurrency(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        List<String> currencies = snapshotRepository.findDistinctCurrencies();
        return currencies.isEmpty() ? null : currencies.get(0);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
