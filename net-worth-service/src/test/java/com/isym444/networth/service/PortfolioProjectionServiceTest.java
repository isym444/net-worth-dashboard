package com.isym444.networth.service;

import com.isym444.networth.dto.MonthlyContributionResponse;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.model.PortfolioSettings;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.repository.PortfolioSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioProjectionServiceTest {

    @Test
    void calculateEstimatedNetWorth1y_compoundsLumpSumAndTwelveMonthlyContributions() {
        // Verified externally: 1000*1.07 + sum_{i=0..11} 100*1.07^((12-i)/12) = 2315.0297... -> 2315.03
        PortfolioProjectionService service =
                new PortfolioProjectionService(mock(PortfolioSettingsRepository.class), mock(NetWorthSnapshotRepository.class));

        assertEquals(2315.03, service.calculateEstimatedNetWorth1y(1000.0, 100.0), 0.001);
    }

    @Test
    void calculateEstimatedNetWorth1y_withZeroContribution_isJustLumpSumCompounded() {
        PortfolioProjectionService service =
                new PortfolioProjectionService(mock(PortfolioSettingsRepository.class), mock(NetWorthSnapshotRepository.class));

        assertEquals(5350.0, service.calculateEstimatedNetWorth1y(5000.0, 0.0), 0.001);
    }

    @Test
    void getMonthlyContribution_defaultsToZero_whenNoSettingsRowExists() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        PortfolioProjectionService service =
                new PortfolioProjectionService(settingsRepository, mock(NetWorthSnapshotRepository.class));

        assertEquals(0.0, service.getMonthlyContribution(), 0.001);
    }

    @Test
    void getMonthlyContribution_returnsPersistedValue_whenSettingsRowExists() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        PortfolioSettings settings = new PortfolioSettings();
        settings.setMonthlyContribution(250.0);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        PortfolioProjectionService service =
                new PortfolioProjectionService(settingsRepository, mock(NetWorthSnapshotRepository.class));

        assertEquals(250.0, service.getMonthlyContribution(), 0.001);
    }

    @Test
    void updateMonthlyContribution_rejectsNegativeValues() {
        PortfolioProjectionService service =
                new PortfolioProjectionService(mock(PortfolioSettingsRepository.class), mock(NetWorthSnapshotRepository.class));

        assertThrows(IllegalArgumentException.class, () -> service.updateMonthlyContribution(-1.0, "GBP"));
    }

    @Test
    void updateMonthlyContribution_persistsSettingAndRecalculatesLatestSnapshotForRequestedCurrency() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        NetWorthSnapshotRepository snapshotRepository = mock(NetWorthSnapshotRepository.class);
        NetWorthSnapshot latest = new NetWorthSnapshot();
        latest.setGrandTotal(1000.0);
        when(snapshotRepository.findFirstByCurrencyOrderByTimestampDescIdDesc("GBP")).thenReturn(Optional.of(latest));
        PortfolioProjectionService service = new PortfolioProjectionService(settingsRepository, snapshotRepository);

        MonthlyContributionResponse response = service.updateMonthlyContribution(100.0, "GBP");

        verify(settingsRepository).save(any(PortfolioSettings.class));
        assertEquals(100.0, response.monthlyContribution());
        assertEquals(2315.03, response.estimatedNetWorth1y(), 0.001);
        assertEquals(2315.03, latest.getEstimatedNetWorth1y(), 0.001);
        verify(snapshotRepository).save(latest);
    }

    @Test
    void updateMonthlyContribution_fallsBackToFirstAlphabeticalCurrency_whenNoneRequested() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        NetWorthSnapshotRepository snapshotRepository = mock(NetWorthSnapshotRepository.class);
        when(snapshotRepository.findDistinctCurrencies()).thenReturn(List.of("EUR", "GBP"));
        NetWorthSnapshot latest = new NetWorthSnapshot();
        latest.setGrandTotal(1000.0);
        when(snapshotRepository.findFirstByCurrencyOrderByTimestampDescIdDesc("EUR")).thenReturn(Optional.of(latest));
        PortfolioProjectionService service = new PortfolioProjectionService(settingsRepository, snapshotRepository);

        service.updateMonthlyContribution(100.0, null);

        verify(snapshotRepository).findFirstByCurrencyOrderByTimestampDescIdDesc("EUR");
    }

    @Test
    void updateMonthlyContribution_returnsNullEstimate_whenNoSnapshotsExistYet() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        NetWorthSnapshotRepository snapshotRepository = mock(NetWorthSnapshotRepository.class);
        when(snapshotRepository.findDistinctCurrencies()).thenReturn(List.of());
        PortfolioProjectionService service = new PortfolioProjectionService(settingsRepository, snapshotRepository);

        MonthlyContributionResponse response = service.updateMonthlyContribution(100.0, null);

        assertNull(response.estimatedNetWorth1y());
        verify(snapshotRepository, never()).save(any());
    }
}
