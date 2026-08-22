package com.isym444.networth.service;

import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
            return new NetWorthHistoryResponse(
                    List.of(), null, null, null, null, null, null, null, null, null, monthlyContribution);
        }

        List<NetWorthSnapshot> snapshots = repository.findByCurrencyOrderByTimestampAsc(resolved);
        if (snapshots.isEmpty()) {
            return new NetWorthHistoryResponse(
                    List.of(), null, null, null, null, null, null, null, null, null, monthlyContribution);
        }

        double baseline = snapshots.get(snapshots.size() - 1).getGrandTotal();
        LocalDate now = LocalDate.now(clock);
        double yearsAt40 = yearsRemaining(now, 40);
        double yearsAt50 = yearsRemaining(now, 50);
        double yearsAt60 = yearsRemaining(now, 60);
        double yearsAt65 = yearsRemaining(now, 65);
        return new NetWorthHistoryResponse(
                downsampleForTable(snapshots, now),
                projectedValue(baseline, yearsAt40),
                projectedValue(baseline, yearsAt50),
                projectedValue(baseline, yearsAt60),
                projectedValue(baseline, yearsAt65),
                portfolioProjectionService.calculateEstimatedNetWorthWithContributions(baseline, monthlyContribution, yearsAt40),
                portfolioProjectionService.calculateEstimatedNetWorthWithContributions(baseline, monthlyContribution, yearsAt50),
                portfolioProjectionService.calculateEstimatedNetWorthWithContributions(baseline, monthlyContribution, yearsAt60),
                portfolioProjectionService.calculateEstimatedNetWorthWithContributions(baseline, monthlyContribution, yearsAt65),
                portfolioProjectionService.calculateEstimatedNetWorth1y(baseline, monthlyContribution),
                monthlyContribution);
    }

    /**
     * Thins out the history table (not the chart, which reads straight from the
     * repository) so old data doesn't dominate: past years keep at most Jan/Jun/Dec
     * (all rows if 3 or fewer already exist), the current year keeps one row per
     * month, and the current month keeps one row per day — except today, which
     * keeps every snapshot taken so far, so the table's last row (and anything the
     * frontend treats as "the latest snapshot") is always genuinely up to date.
     */
    List<NetWorthSnapshot> downsampleForTable(List<NetWorthSnapshot> snapshots, LocalDate today) {
        Map<Integer, List<NetWorthSnapshot>> byYear = new LinkedHashMap<>();
        for (NetWorthSnapshot snapshot : snapshots) {
            byYear.computeIfAbsent(dateOf(snapshot).getYear(), y -> new ArrayList<>()).add(snapshot);
        }

        List<NetWorthSnapshot> result = new ArrayList<>();
        for (Map.Entry<Integer, List<NetWorthSnapshot>> entry : byYear.entrySet()) {
            if (entry.getKey() == today.getYear()) {
                result.addAll(downsampleCurrentYear(entry.getValue(), today));
            } else {
                result.addAll(downsamplePastYear(entry.getValue()));
            }
        }
        return result;
    }

    private List<NetWorthSnapshot> downsamplePastYear(List<NetWorthSnapshot> yearSnapshots) {
        if (yearSnapshots.size() <= 3) {
            return yearSnapshots;
        }
        Map<Integer, List<NetWorthSnapshot>> byMonth = groupByMonth(yearSnapshots);
        List<NetWorthSnapshot> picks = new ArrayList<>();
        for (int targetMonth : List.of(1, 6, 12)) {
            NetWorthSnapshot pick = byMonth.get(nearestAvailableMonth(byMonth.keySet(), targetMonth)).get(0);
            if (!picks.contains(pick)) {
                picks.add(pick);
            }
        }
        picks.sort(Comparator.comparing(NetWorthSnapshot::getTimestamp));
        return picks;
    }

    // Ties (equal distance to two candidate months) favor the earlier month, since
    // TreeSet iterates ascending and only a strictly closer month replaces the pick.
    private int nearestAvailableMonth(Set<Integer> availableMonths, int targetMonth) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int month : new TreeSet<>(availableMonths)) {
            int distance = Math.abs(month - targetMonth);
            if (distance < bestDistance) {
                best = month;
                bestDistance = distance;
            }
        }
        return best;
    }

    private List<NetWorthSnapshot> downsampleCurrentYear(List<NetWorthSnapshot> yearSnapshots, LocalDate today) {
        List<NetWorthSnapshot> result = new ArrayList<>();
        for (Map.Entry<Integer, List<NetWorthSnapshot>> entry : groupByMonth(yearSnapshots).entrySet()) {
            if (entry.getKey() == today.getMonthValue()) {
                result.addAll(downsampleCurrentMonth(entry.getValue(), today));
            } else {
                result.add(entry.getValue().get(0));
            }
        }
        return result;
    }

    private List<NetWorthSnapshot> downsampleCurrentMonth(List<NetWorthSnapshot> monthSnapshots, LocalDate today) {
        Map<LocalDate, List<NetWorthSnapshot>> byDay = new LinkedHashMap<>();
        for (NetWorthSnapshot snapshot : monthSnapshots) {
            byDay.computeIfAbsent(dateOf(snapshot), d -> new ArrayList<>()).add(snapshot);
        }
        List<NetWorthSnapshot> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<NetWorthSnapshot>> entry : byDay.entrySet()) {
            if (entry.getKey().equals(today)) {
                result.addAll(entry.getValue());
            } else {
                result.add(entry.getValue().get(0));
            }
        }
        return result;
    }

    // Relies on the input already being timestamp-ascending, so each month's/year's
    // bucket is built in chronological order and index 0 is always its earliest row.
    private Map<Integer, List<NetWorthSnapshot>> groupByMonth(List<NetWorthSnapshot> snapshots) {
        Map<Integer, List<NetWorthSnapshot>> byMonth = new LinkedHashMap<>();
        for (NetWorthSnapshot snapshot : snapshots) {
            byMonth.computeIfAbsent(dateOf(snapshot).getMonthValue(), m -> new ArrayList<>()).add(snapshot);
        }
        return byMonth;
    }

    private LocalDate dateOf(NetWorthSnapshot snapshot) {
        return LocalDate.ofInstant(snapshot.getTimestamp(), clock.getZone());
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
