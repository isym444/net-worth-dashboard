package com.isym444.networth.service;

import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        // Distinct days within the current month (1996-06) so table downsampling keeps both.
        NetWorthSnapshot older = snapshotAt(LocalDate.of(1996, 6, 1), 1000.0);
        NetWorthSnapshot latest = snapshotAt(LocalDate.of(1996, 6, 14), 2000.0);
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
    void getHistory_includesWithContributionsProjections_computedViaPortfolioProjectionService() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        NetWorthSnapshot older = snapshotAt(LocalDate.of(1996, 6, 1), 1000.0);
        NetWorthSnapshot latest = snapshotAt(LocalDate.of(1996, 6, 14), 2000.0);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of(older, latest));

        PortfolioProjectionService portfolioProjectionService = mock(PortfolioProjectionService.class);
        when(portfolioProjectionService.getMonthlyContribution()).thenReturn(150.0);
        when(portfolioProjectionService.calculateEstimatedNetWorthWithContributions(eq(2000.0), eq(150.0), anyDouble()))
                .thenReturn(9999.0);

        LocalDate fixedToday = LocalDate.of(1996, 6, 14);
        Clock fixedClock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        NetWorthHistoryService service = new NetWorthHistoryService(repository, portfolioProjectionService, fixedClock);

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(9999.0, response.expectedNetWorthWithContributionsAt40());
        assertEquals(9999.0, response.expectedNetWorthWithContributionsAt50());
        assertEquals(9999.0, response.expectedNetWorthWithContributionsAt60());
        assertEquals(9999.0, response.expectedNetWorthWithContributionsAt65());

        verify(portfolioProjectionService).calculateEstimatedNetWorthWithContributions(
                2000.0, 150.0, service.yearsRemaining(fixedToday, 40));
        verify(portfolioProjectionService).calculateEstimatedNetWorthWithContributions(
                2000.0, 150.0, service.yearsRemaining(fixedToday, 50));
        verify(portfolioProjectionService).calculateEstimatedNetWorthWithContributions(
                2000.0, 150.0, service.yearsRemaining(fixedToday, 60));
        verify(portfolioProjectionService).calculateEstimatedNetWorthWithContributions(
                2000.0, 150.0, service.yearsRemaining(fixedToday, 65));
    }

    @Test
    void getHistory_computesEstimatedNetWorth1y_onTheFlyFromBaselineAndMonthlyContribution() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        NetWorthSnapshot older = snapshotAt(LocalDate.of(1996, 6, 1), 1000.0);
        NetWorthSnapshot latest = snapshotAt(LocalDate.of(1996, 6, 14), 2000.0);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of(older, latest));

        PortfolioProjectionService portfolioProjectionService = mock(PortfolioProjectionService.class);
        when(portfolioProjectionService.getMonthlyContribution()).thenReturn(150.0);
        when(portfolioProjectionService.calculateEstimatedNetWorth1y(2000.0, 150.0)).thenReturn(4242.0);

        LocalDate fixedToday = LocalDate.of(1996, 6, 14);
        Clock fixedClock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        NetWorthHistoryService service = new NetWorthHistoryService(repository, portfolioProjectionService, fixedClock);

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(4242.0, response.estimatedNetWorth1y());
    }

    @Test
    void getHistory_reportsThePeakOverAllSnapshots_evenWhenTheTableRowsAreThinnedAway() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        // September's peak sits mid-month; once October starts, past months keep one row (the first), so the peak is thinned out.
        NetWorthSnapshot sepFirst = snapshotAt(LocalDate.of(2026, 9, 1), 48000.0);
        NetWorthSnapshot sepPeak = snapshotAt(LocalDate.of(2026, 9, 15), 52000.0);
        NetWorthSnapshot octFirst = snapshotAt(LocalDate.of(2026, 10, 1), 49000.0);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of(sepFirst, sepPeak, octFirst));

        PortfolioProjectionService portfolioProjectionService = mock(PortfolioProjectionService.class);
        LocalDate fixedToday = LocalDate.of(2026, 10, 1);
        Clock fixedClock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        NetWorthHistoryService service = new NetWorthHistoryService(repository, portfolioProjectionService, fixedClock);

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(52000.0, response.peakNetWorth());
        assertEquals(false, response.snapshots().contains(sepPeak));
    }

    @Test
    void getHistory_fallsBackToFirstAlphabeticalCurrency_whenNoneRequested() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        when(repository.findDistinctCurrencies()).thenReturn(List.of("EUR", "GBP"));
        when(repository.findByCurrencyOrderByTimestampAsc("EUR"))
                .thenReturn(List.of(snapshotWithGrandTotal(500.0, Instant.now())));
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

    @Test
    void downsampleForTable_pastYear_keepsAllSnapshots_whenAtMostThree() {
        NetWorthSnapshot jan = snapshotAt(LocalDate.of(2020, 1, 10), 100);
        NetWorthSnapshot jun = snapshotAt(LocalDate.of(2020, 6, 10), 200);
        NetWorthSnapshot dec = snapshotAt(LocalDate.of(2020, 12, 10), 300);

        List<NetWorthSnapshot> result =
                service.downsampleForTable(List.of(jan, jun, dec), LocalDate.of(2024, 1, 1));

        assertEquals(List.of(jan, jun, dec), result);
    }

    @Test
    void downsampleForTable_pastYear_keepsOnlyJanJuneDec_whenMoreThanThreeAndExactMonthsPresent() {
        NetWorthSnapshot jan = snapshotAt(LocalDate.of(2020, 1, 5), 100);
        NetWorthSnapshot mar = snapshotAt(LocalDate.of(2020, 3, 5), 150);
        NetWorthSnapshot jun = snapshotAt(LocalDate.of(2020, 6, 5), 200);
        NetWorthSnapshot sep = snapshotAt(LocalDate.of(2020, 9, 5), 250);
        NetWorthSnapshot dec = snapshotAt(LocalDate.of(2020, 12, 5), 300);

        List<NetWorthSnapshot> result =
                service.downsampleForTable(List.of(jan, mar, jun, sep, dec), LocalDate.of(2024, 1, 1));

        assertEquals(List.of(jan, jun, dec), result);
    }

    @Test
    void downsampleForTable_pastYear_picksEarliestSnapshotInMonth_whenMultipleInTargetMonth() {
        NetWorthSnapshot janEarly = snapshotAt(LocalDate.of(2020, 1, 2), 100);
        NetWorthSnapshot janLate = snapshotAt(LocalDate.of(2020, 1, 28), 110);
        NetWorthSnapshot jun = snapshotAt(LocalDate.of(2020, 6, 5), 200);
        NetWorthSnapshot decA = snapshotAt(LocalDate.of(2020, 12, 3), 300);
        NetWorthSnapshot decB = snapshotAt(LocalDate.of(2020, 12, 20), 310);

        List<NetWorthSnapshot> result = service.downsampleForTable(
                List.of(janEarly, janLate, jun, decA, decB), LocalDate.of(2024, 1, 1));

        assertEquals(List.of(janEarly, jun, decA), result);
    }

    @Test
    void downsampleForTable_pastYear_fallsBackToNearestMonth_whenTargetMonthMissing() {
        // No January or December data. Jan target -> nearest is Feb; Dec target -> nearest is Nov (closer than Aug).
        NetWorthSnapshot feb = snapshotAt(LocalDate.of(2020, 2, 10), 100);
        NetWorthSnapshot jun = snapshotAt(LocalDate.of(2020, 6, 10), 200);
        NetWorthSnapshot aug = snapshotAt(LocalDate.of(2020, 8, 10), 250);
        NetWorthSnapshot nov = snapshotAt(LocalDate.of(2020, 11, 10), 300);

        List<NetWorthSnapshot> result =
                service.downsampleForTable(List.of(feb, jun, aug, nov), LocalDate.of(2024, 1, 1));

        assertEquals(List.of(feb, jun, nov), result);
    }

    @Test
    void downsampleForTable_pastYear_tieBreaksTowardEarlierMonth_whenNearestMonthDistanceTies() {
        // June target: March (dist 3) and September (dist 3) tie -> March wins.
        NetWorthSnapshot jan = snapshotAt(LocalDate.of(2020, 1, 10), 50);
        NetWorthSnapshot mar = snapshotAt(LocalDate.of(2020, 3, 10), 100);
        NetWorthSnapshot sep = snapshotAt(LocalDate.of(2020, 9, 10), 200);
        NetWorthSnapshot dec = snapshotAt(LocalDate.of(2020, 12, 10), 300);

        List<NetWorthSnapshot> result =
                service.downsampleForTable(List.of(jan, mar, sep, dec), LocalDate.of(2024, 1, 1));

        assertEquals(List.of(jan, mar, dec), result);
    }

    @Test
    void downsampleForTable_pastYear_dedupesPicks_whenMultipleTargetsResolveToSameMonth() {
        NetWorthSnapshot a = snapshotAt(LocalDate.of(2020, 7, 1), 100);
        NetWorthSnapshot b = snapshotAt(LocalDate.of(2020, 7, 8), 110);
        NetWorthSnapshot c = snapshotAt(LocalDate.of(2020, 7, 15), 120);
        NetWorthSnapshot d = snapshotAt(LocalDate.of(2020, 7, 22), 130);

        List<NetWorthSnapshot> result =
                service.downsampleForTable(List.of(a, b, c, d), LocalDate.of(2024, 1, 1));

        assertEquals(List.of(a), result);
    }

    @Test
    void downsampleForTable_currentYear_keepsEarliestSnapshotPerNonCurrentMonth() {
        LocalDate today = LocalDate.of(2024, 8, 15);
        NetWorthSnapshot janEarly = snapshotAt(LocalDate.of(2024, 1, 3), 100);
        NetWorthSnapshot janLate = snapshotAt(LocalDate.of(2024, 1, 20), 110);
        NetWorthSnapshot marOnly = snapshotAt(LocalDate.of(2024, 3, 10), 150);

        List<NetWorthSnapshot> result =
                service.downsampleForTable(List.of(janEarly, janLate, marOnly), today);

        assertEquals(List.of(janEarly, marOnly), result);
    }

    @Test
    void downsampleForTable_currentYear_currentMonth_keepsEarliestSnapshotPerDay() {
        LocalDate today = LocalDate.of(2024, 8, 15);
        NetWorthSnapshot day1Morning = snapshotAt(LocalDate.of(2024, 8, 1), 6, 100);
        NetWorthSnapshot day1Evening = snapshotAt(LocalDate.of(2024, 8, 1), 20, 105);
        NetWorthSnapshot day2 = snapshotAt(LocalDate.of(2024, 8, 2), 6, 110);

        List<NetWorthSnapshot> result =
                service.downsampleForTable(List.of(day1Morning, day1Evening, day2), today);

        assertEquals(List.of(day1Morning, day2), result);
    }

    @Test
    void downsampleForTable_currentYear_currentMonth_keepsEverySnapshotFromToday() {
        LocalDate today = LocalDate.of(2024, 8, 15);
        NetWorthSnapshot earlierDay = snapshotAt(LocalDate.of(2024, 8, 1), 6, 100);
        NetWorthSnapshot todayMorning = snapshotAt(today, 6, 110);
        NetWorthSnapshot todayNoon = snapshotAt(today, 12, 115);
        NetWorthSnapshot todayEvening = snapshotAt(today, 20, 120);

        List<NetWorthSnapshot> result = service.downsampleForTable(
                List.of(earlierDay, todayMorning, todayNoon, todayEvening), today);

        assertEquals(List.of(earlierDay, todayMorning, todayNoon, todayEvening), result);
    }

    @Test
    void getHistory_downsamplesTableSnapshots_butProjectionsStillUseLatestRawSnapshot() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        // Both in the current year, non-current month (March) so the table still
        // downsamples to first-of-month-only, even though today's own snapshots
        // would now all be kept — verifying baseline/projections always use the
        // true latest raw snapshot regardless of what the table displays.
        NetWorthSnapshot earlier = snapshotAt(LocalDate.of(1996, 3, 5), 1000);
        NetWorthSnapshot later = snapshotAt(LocalDate.of(1996, 3, 20), 2000);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of(earlier, later));

        LocalDate fixedToday = LocalDate.of(1996, 6, 14);
        Clock fixedClock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        NetWorthHistoryService service =
                new NetWorthHistoryService(repository, mock(PortfolioProjectionService.class), fixedClock);

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(List.of(earlier), response.snapshots());
        assertEquals(service.projectedValue(2000.0, service.yearsRemaining(fixedToday, 40)),
                response.expectedNetWorthAt40(), 0.0001);
    }

    @Test
    void getHistory_tableLastRow_isTrueLatestSnapshot_whenMultipleSnapshotsTakenToday() {
        NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);
        NetWorthSnapshot morning = snapshotAt(LocalDate.of(1996, 6, 14), 6, 1000);
        NetWorthSnapshot evening = snapshotAt(LocalDate.of(1996, 6, 14), 20, 2000);
        when(repository.findByCurrencyOrderByTimestampAsc("GBP")).thenReturn(List.of(morning, evening));

        LocalDate fixedToday = LocalDate.of(1996, 6, 14);
        Clock fixedClock = Clock.fixed(fixedToday.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        NetWorthHistoryService service =
                new NetWorthHistoryService(repository, mock(PortfolioProjectionService.class), fixedClock);

        NetWorthHistoryResponse response = service.getHistory("GBP");

        assertEquals(List.of(morning, evening), response.snapshots());
    }

    private NetWorthSnapshot snapshotWithGrandTotal(double grandTotal, Instant timestamp) {
        NetWorthSnapshot snapshot = new NetWorthSnapshot();
        snapshot.setGrandTotal(grandTotal);
        snapshot.setTimestamp(timestamp);
        return snapshot;
    }

    private NetWorthSnapshot snapshotAt(LocalDate date, double grandTotal) {
        return snapshotWithGrandTotal(grandTotal, date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private NetWorthSnapshot snapshotAt(LocalDate date, int hour, double grandTotal) {
        return snapshotWithGrandTotal(grandTotal, date.atTime(hour, 0).toInstant(ZoneOffset.UTC));
    }
}
