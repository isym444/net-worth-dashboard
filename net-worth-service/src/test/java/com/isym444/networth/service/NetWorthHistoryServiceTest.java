package com.isym444.networth.service;

import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetWorthHistoryServiceTest {

    private final NetWorthHistoryService service =
            new NetWorthHistoryService(mock(NetWorthSnapshotRepository.class),
                    mock(PortfolioProjectionService.class), Clock.systemUTC());

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

    @Test
    void getHistory_usesProvidedCurrency_andComputesProjectionsFromLatestSnapshot() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        NetWorthSnapshot older = snapshotWithGrandTotal(1000.0);
        NetWorthSnapshot latest = snapshotWithGrandTotal(2000.0);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of(older, latest));

        // Deliberately NOT a round number of years before any target age (e.g. exactly
        // 50 years before 2046-06-14 is 18262 days, not 18262.5 = 50*365.25, because of
        // how leap days fall in that window) — so the expected values below are derived
        // by calling the already-tested yearsRemaining()/projectedValue() directly with
        // this same fixed date, rather than assuming a hand-computed round number.
        LocalDate fixedToday = LocalDate.of(1996, 6, 14);
        Clock fixedClock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        NetWorthHistoryService service =
                new NetWorthHistoryService(repository, mock(PortfolioProjectionService.class), fixedClock);

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(List.of(older, latest), response.snapshots());
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 40)),
                response.expectedNetWorthAt40(), 0.0001);
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 50)),
                response.expectedNetWorthAt50(), 0.0001);
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 60)),
                response.expectedNetWorthAt60(), 0.0001);
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 65)),
                response.expectedNetWorthAt65(), 0.0001);
        verify(repository, never()).findDistinctCurrencies();
    }

    @Test
    void getHistory_fallsBackToFirstAlphabeticalCurrency_whenNoneRequested() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        when(repository.findDistinctCurrencies()).thenReturn(List.of("EUR", "GBP"));
        when(repository.findByCurrencyOrderByTimestampAsc("EUR"))
                .thenReturn(List.of(snapshotWithGrandTotal(500.0)));
        NetWorthHistoryService service =
                new NetWorthHistoryService(repository, mock(PortfolioProjectionService.class), Clock.systemUTC());

        NetWorthHistoryResponse response = service.getHistory(null);

        assertEquals(1, response.snapshots().size());
        verify(repository).findByCurrencyOrderByTimestampAsc("EUR");
    }

    @Test
    void getHistory_returnsEmptyResponse_whenNoCurrenciesExist() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        when(repository.findDistinctCurrencies()).thenReturn(List.of());
        NetWorthHistoryService service =
                new NetWorthHistoryService(repository, mock(PortfolioProjectionService.class), Clock.systemUTC());

        NetWorthHistoryResponse response = service.getHistory(null);

        assertEquals(List.of(), response.snapshots());
        assertNull(response.expectedNetWorthAt40());
        assertNull(response.expectedNetWorthAt50());
        assertNull(response.expectedNetWorthAt60());
        assertNull(response.expectedNetWorthAt65());
        verify(repository, never()).findByCurrencyOrderByTimestampAsc(anyString());
    }

    @Test
    void getHistory_returnsEmptyResponse_whenCurrencyHasNoSnapshots() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of());
        NetWorthHistoryService service =
                new NetWorthHistoryService(repository, mock(PortfolioProjectionService.class), Clock.systemUTC());

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(List.of(), response.snapshots());
        assertNull(response.expectedNetWorthAt40());
    }

    private NetWorthSnapshot snapshotWithGrandTotal(double grandTotal) {
        NetWorthSnapshot snapshot = new NetWorthSnapshot();
        snapshot.setGrandTotal(grandTotal);
        return snapshot;
    }
}
