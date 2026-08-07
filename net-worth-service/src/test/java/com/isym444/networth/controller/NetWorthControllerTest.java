package com.isym444.networth.controller;

import com.isym444.networth.dto.NetWorthHistoryResponse;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import com.isym444.networth.service.ChartService;
import com.isym444.networth.service.NetWorthHistoryService;
import com.isym444.networth.service.NetWorthSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetWorthControllerTest {

    @Test
    void history_delegatesToHistoryServiceWithTheRequestedCurrency() {
        NetWorthHistoryService historyService = mock(NetWorthHistoryService.class);
        NetWorthHistoryResponse expected = new NetWorthHistoryResponse(List.of(), null, null, null, null);
        when(historyService.getHistory("GBP")).thenReturn(expected);

        NetWorthController controller = new NetWorthController(
                mock(NetWorthSnapshotService.class),
                historyService,
                mock(NetWorthSnapshotRepository.class),
                mock(ChartService.class));

        ResponseEntity<NetWorthHistoryResponse> response = controller.history("GBP");

        assertEquals(200, response.getStatusCode().value());
        assertSame(expected, response.getBody());
        verify(historyService).getHistory("GBP");
    }
}
