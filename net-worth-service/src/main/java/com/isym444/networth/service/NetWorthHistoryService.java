package com.isym444.networth.service;

import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

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
    private final Clock clock;

    public NetWorthHistoryService(NetWorthSnapshotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
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
