package com.isym444.networth.service;

import com.isym444.networth.model.PortfolioSettings;
import com.isym444.networth.repository.PortfolioSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioProjectionServiceTest {

    @Test
    void calculateEstimatedNetWorth1y_compoundsLumpSumAndTwelveMonthlyContributions() {
        // Verified externally: 1000*1.07 + sum_{i=0..11} 100*1.07^((12-i)/12) = 2315.0297... -> 2315.03
        PortfolioProjectionService service = new PortfolioProjectionService(mock(PortfolioSettingsRepository.class));

        assertEquals(2315.03, service.calculateEstimatedNetWorth1y(1000.0, 100.0), 0.001);
    }

    @Test
    void calculateEstimatedNetWorth1y_withZeroContribution_isJustLumpSumCompounded() {
        PortfolioProjectionService service = new PortfolioProjectionService(mock(PortfolioSettingsRepository.class));

        assertEquals(5350.0, service.calculateEstimatedNetWorth1y(5000.0, 0.0), 0.001);
    }

    @Test
    void calculateEstimatedNetWorthWithContributions_atOneYear_matchesCalculateEstimatedNetWorth1y() {
        PortfolioProjectionService service = new PortfolioProjectionService(mock(PortfolioSettingsRepository.class));

        assertEquals(2315.03, service.calculateEstimatedNetWorthWithContributions(1000.0, 100.0, 1.0), 0.001);
    }

    @Test
    void calculateEstimatedNetWorthWithContributions_compoundsOverMultipleYears() {
        // Verified externally (Python, same loop as the production formula): 3722.11
        PortfolioProjectionService service = new PortfolioProjectionService(mock(PortfolioSettingsRepository.class));

        assertEquals(3722.11, service.calculateEstimatedNetWorthWithContributions(1000.0, 100.0, 2.0), 0.001);
    }

    @Test
    void calculateEstimatedNetWorthWithContributions_roundsMonthCountForFractionalYears() {
        // years=0.5 -> 6 monthly contributions. Verified externally: 2374.81
        PortfolioProjectionService service = new PortfolioProjectionService(mock(PortfolioSettingsRepository.class));

        assertEquals(2374.81, service.calculateEstimatedNetWorthWithContributions(2000.0, 50.0, 0.5), 0.001);
    }

    @Test
    void calculateEstimatedNetWorthWithContributions_withZeroYears_returnsGrandTotalUnchanged() {
        PortfolioProjectionService service = new PortfolioProjectionService(mock(PortfolioSettingsRepository.class));

        assertEquals(5000.0, service.calculateEstimatedNetWorthWithContributions(5000.0, 100.0, 0.0), 0.001);
    }

    @Test
    void getMonthlyContribution_defaultsToZero_whenNoSettingsRowExists() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        PortfolioProjectionService service = new PortfolioProjectionService(settingsRepository);

        assertEquals(0.0, service.getMonthlyContribution(), 0.001);
    }

    @Test
    void getMonthlyContribution_returnsPersistedValue_whenSettingsRowExists() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        PortfolioSettings settings = new PortfolioSettings();
        settings.setMonthlyContribution(250.0);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        PortfolioProjectionService service = new PortfolioProjectionService(settingsRepository);

        assertEquals(250.0, service.getMonthlyContribution(), 0.001);
    }

    @Test
    void updateMonthlyContribution_rejectsNegativeValues() {
        PortfolioProjectionService service = new PortfolioProjectionService(mock(PortfolioSettingsRepository.class));

        assertThrows(IllegalArgumentException.class, () -> service.updateMonthlyContribution(-1.0));
    }

    @Test
    void updateMonthlyContribution_persistsSettingAndReturnsIt() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        PortfolioProjectionService service = new PortfolioProjectionService(settingsRepository);

        double result = service.updateMonthlyContribution(100.0);

        verify(settingsRepository).save(any(PortfolioSettings.class));
        assertEquals(100.0, result);
    }

    @Test
    void updateMonthlyContribution_updatesExistingSettingsRow_ratherThanCreatingASecondOne() {
        PortfolioSettingsRepository settingsRepository = mock(PortfolioSettingsRepository.class);
        PortfolioSettings existing = new PortfolioSettings();
        existing.setId(PortfolioSettings.SINGLETON_ID);
        existing.setMonthlyContribution(50.0);
        when(settingsRepository.findById(PortfolioSettings.SINGLETON_ID)).thenReturn(Optional.of(existing));
        PortfolioProjectionService service = new PortfolioProjectionService(settingsRepository);

        service.updateMonthlyContribution(75.0);

        assertEquals(75.0, existing.getMonthlyContribution());
        verify(settingsRepository).save(existing);
    }
}
