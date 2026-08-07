package com.isym444.networth.service;

import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class NetWorthHistoryServiceTest {

    private final NetWorthHistoryService service =
            new NetWorthHistoryService(mock(NetWorthSnapshotRepository.class), Clock.systemUTC());

    @Test
    void yearsRemaining_returnsZero_whenNowIsExactlyTheTargetBirthday() {
        LocalDate now = LocalDate.of(2036, 6, 14); // birth date (1996-06-14) + 40 years
        assertEquals(0.0, service.yearsRemaining(now, 40), 0.001);
    }

    @Test
    void yearsRemaining_returnsFortyYears_whenNowIsTheBirthDate() {
        LocalDate now = LocalDate.of(1996, 6, 14);
        assertEquals(40.0, service.yearsRemaining(now, 40), 0.01);
    }

    @Test
    void yearsRemaining_isNegative_whenTargetAgeAlreadyPassed() {
        LocalDate now = LocalDate.of(2040, 6, 14); // 4 years after turning 40
        assertEquals(-4.0, service.yearsRemaining(now, 40), 0.01);
    }

    @Test
    void projectedValue_appliesCompoundInterestAtSevenPercent() {
        // 1000 * 1.07^10 = 1967.151357... -> rounds to 1967.15
        assertEquals(1967.15, service.projectedValue(1000.0, 10.0), 0.001);
    }

    @Test
    void projectedValue_clampsNegativeYearsToZero() {
        assertEquals(1000.0, service.projectedValue(1000.0, -5.0), 0.001);
    }

    @Test
    void projectedValue_returnsBaselineWhenYearsIsZero() {
        assertEquals(1000.0, service.projectedValue(1000.0, 0.0), 0.001);
    }
}
