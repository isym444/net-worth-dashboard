package com.isym444.networth.service;

import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Read-side service for /history: fetches persisted snapshots and derives
 * projected future net worth via simple compound interest. Kept separate
 * from NetWorthSnapshotService, which is dedicated to creating snapshots
 * from external APIs.
 */
@Service
public class NetWorthHistoryService {

    // Single-user personal finance app — no config plumbing needed for either.
    private static final LocalDate BIRTH_DATE = LocalDate.of(1996, 6, 14);
    private static final double ANNUAL_GROWTH_RATE = 0.07;

    private final NetWorthSnapshotRepository repository;
    private final PortfolioProjectionService portfolioProjectionService;
    private final Clock clock;

    public NetWorthHistoryService(NetWorthSnapshotRepository repository,
                                   PortfolioProjectionService portfolioProjectionService,
                                   Clock clock) {
        this.repository = repository;
        this.portfolioProjectionService = portfolioProjectionService;
        this.clock = clock;
    }

    public NetWorthHistoryResponse getHistory(String currency) {
        double monthlyContribution = portfolioProjectionService.getMonthlyContribution();

        String resolved = resolveCurrency(currency);
        if (resolved == null) {
            return new NetWorthHistoryResponse(List.of(), null, null, null, null, monthlyContribution);
        }

        List<NetWorthSnapshot> snapshots = repository.findByCurrencyOrderByTimestampAsc(resolved);
        if (snapshots.isEmpty()) {
            return new NetWorthHistoryResponse(List.of(), null, null, null, null, monthlyContribution);
        }

        double baseline = snapshots.get(snapshots.size() - 1).getGrandTotal();
        LocalDate now = LocalDate.now(clock);
        return new NetWorthHistoryResponse(
                snapshots,
                projectedValue(baseline, yearsRemaining(now, 40)),
                projectedValue(baseline, yearsRemaining(now, 50)),
                projectedValue(baseline, yearsRemaining(now, 60)),
                projectedValue(baseline, yearsRemaining(now, 65)),
                monthlyContribution);
    }

    // Assumes a single-currency dataset: with no ?currency= param, falls back to
    // whichever currency sorts first alphabetically among logged snapshots. Same
    // fallback NetWorthController.resolveCurrency uses for /chart.
    private String resolveCurrency(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        List<String> currencies = repository.findDistinctCurrencies();
        return currencies.isEmpty() ? null : currencies.get(0);
    }

    double yearsRemaining(LocalDate now, int targetAge) {
        LocalDate targetDate = BIRTH_DATE.plusYears(targetAge);
        return ChronoUnit.DAYS.between(now, targetDate) / 365.25;
    }

    double projectedValue(double baseline, double years) {
        double effectiveYears = Math.max(0, years);
        return round2(baseline * Math.pow(1 + ANNUAL_GROWTH_RATE, effectiveYears));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
