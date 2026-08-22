package com.isym444.networth.controller;

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

class NetWorthControllerTest {

    @Test
    void history_delegatesToHistoryServiceWithTheRequestedCurrency() {
        NetWorthHistoryResponse expected = new NetWorthHistoryResponse(List.of(), null, null, null, null, null);
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
