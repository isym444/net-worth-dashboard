package com.isym444.networth.controller;

import com.isym444.networth.dto.MonthlyContributionRequest;
import com.isym444.networth.dto.MonthlyContributionResponse;
import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.service.AmexInsightService;
import com.isym444.networth.service.ChartService;
import com.isym444.networth.service.NetWorthHistoryService;
import com.isym444.networth.service.NetWorthSnapshotService;
import com.isym444.networth.service.PortfolioProjectionService;
import com.isym444.networth.service.YnabTransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetWorthControllerTest {

    @Test
    void history_delegatesToHistoryServiceWithTheRequestedCurrency() {
        NetWorthHistoryResponse expected =
                new NetWorthHistoryResponse(List.of(), null, null, null, null, null, null, null, null, null, null);
        RecordingHistoryService historyService = new RecordingHistoryService(expected);

        NetWorthController controller = new NetWorthController(
                mock(NetWorthSnapshotService.class),
                historyService,
                mock(NetWorthSnapshotRepository.class),
                mock(ChartService.class),
                mock(YnabTransactionService.class),
                mock(AmexInsightService.class),
                mock(PortfolioProjectionService.class));

        ResponseEntity<NetWorthHistoryResponse> response = controller.history("GBP");

        assertEquals(200, response.getStatusCode().value());
        assertSame(expected, response.getBody());
        assertEquals("GBP", historyService.receivedCurrency);
    }

    @Test
    void updateMonthlyContribution_composesPersistedValueWithFreshHistoryProjections() {
        // estimatedNetWorth1y and the with-contributions age fields both come from this
        // fresh history fetch now, not from PortfolioProjectionService directly — nothing
        // about the estimate is persisted, so there's nothing to go stale or miss a row.
        NetWorthHistoryResponse freshHistory = new NetWorthHistoryResponse(
                List.of(), 1.0, 2.0, 3.0, 4.0, 100.0, 200.0, 300.0, 400.0, 999.0, 50.0);
        RecordingHistoryService historyService = new RecordingHistoryService(freshHistory);
        PortfolioProjectionService portfolioProjectionService = mock(PortfolioProjectionService.class);
        when(portfolioProjectionService.updateMonthlyContribution(50.0)).thenReturn(50.0);

        NetWorthController controller = new NetWorthController(
                mock(NetWorthSnapshotService.class),
                historyService,
                mock(NetWorthSnapshotRepository.class),
                mock(ChartService.class),
                mock(YnabTransactionService.class),
                mock(AmexInsightService.class),
                portfolioProjectionService);

        ResponseEntity<MonthlyContributionResponse> response =
                controller.updateMonthlyContribution(new MonthlyContributionRequest(50.0), "GBP");

        MonthlyContributionResponse body = response.getBody();
        assertEquals(50.0, body.monthlyContribution());
        assertEquals(999.0, body.estimatedNetWorth1y());
        assertEquals(100.0, body.expectedNetWorthWithContributionsAt40());
        assertEquals(200.0, body.expectedNetWorthWithContributionsAt50());
        assertEquals(300.0, body.expectedNetWorthWithContributionsAt60());
        assertEquals(400.0, body.expectedNetWorthWithContributionsAt65());
        assertEquals("GBP", historyService.receivedCurrency);
    }

    /**
     * Hand-written stub in place of a Mockito mock. NetWorthHistoryService is a
     * concrete class, and mocking a concrete class requires byte-buddy to
     * instrument its bytecode at runtime — which fails on JDKs newer than what
     * this project's bundled Mockito/byte-buddy version supports (e.g. JDK 26,
     * see "Java 26 is not supported by the current version of Byte Buddy").
     * Subclassing sidesteps that instrumentation entirely, so the test runs on
     * any JDK the project's own bytecode targets.
     */
    private static class RecordingHistoryService extends NetWorthHistoryService {
        private final NetWorthHistoryResponse response;
        private String receivedCurrency;

        RecordingHistoryService(NetWorthHistoryResponse response) {
            super(null, null, null);
            this.response = response;
        }

        @Override
        public NetWorthHistoryResponse getHistory(String currency) {
            this.receivedCurrency = currency;
            return response;
        }
    }
}
