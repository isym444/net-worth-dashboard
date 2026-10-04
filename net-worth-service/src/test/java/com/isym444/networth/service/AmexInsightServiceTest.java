package com.isym444.networth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.isym444.networth.client.GeminiClient;
import com.isym444.networth.dto.AmexFollowUpResponse;
import com.isym444.networth.dto.AmexTransaction;
import com.isym444.networth.model.NetWorthSnapshot;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AmexInsightServiceTest {

    private final YnabTransactionService ynab = mock(YnabTransactionService.class);
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);

    private AmexInsightService service() {
        AmexInsightService service = new AmexInsightService(
                ynab, gemini, repository, new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(service, "apiKey", "gemini-key");
        ReflectionTestUtils.setField(service, "model", "gemini-model");
        return service;
    }

    private void givenLatestCommentary(String commentary) {
        NetWorthSnapshot row = new NetWorthSnapshot();
        row.setAiCommentary(commentary);
        when(repository.findDistinctCurrencies()).thenReturn(List.of("GBP"));
        when(repository.findFirstByCurrencyOrderByTimestampDescIdDesc("GBP")).thenReturn(Optional.of(row));
    }

    @Test
    void askFollowUp_sendsTheQuestionTheLatestSummaryAndTheTransactionsToGemini() {
        givenLatestCommentary("Events & Live Entertainment: £198.00");
        when(ynab.getRecentAmexTransactions(30)).thenReturn(List.of(
                new AmexTransaction(LocalDate.of(2026, 9, 12), "Royal Albert Hall", "Fun", -120.0, "proms", "Amex")));
        when(gemini.generateContent(eq("gemini-model"), anyString(), eq("gemini-key"))).thenReturn("A Proms concert.");

        AmexFollowUpResponse response = service().askFollowUp("What were the events?");

        assertEquals("A Proms concert.", response.answer());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateContent(eq("gemini-model"), prompt.capture(), eq("gemini-key"));
        assertTrue(prompt.getValue().contains("What were the events?"));
        assertTrue(prompt.getValue().contains("Events & Live Entertainment: £198.00"));
        assertTrue(prompt.getValue().contains("Royal Albert Hall"));
        assertTrue(prompt.getValue().contains("\"amount\":120.0"), "amounts use the prompt's positive-means-spent convention");
    }

    @Test
    void askFollowUp_worksWithoutAStoredSummary() {
        when(repository.findDistinctCurrencies()).thenReturn(List.of());
        when(ynab.getRecentAmexTransactions(30)).thenReturn(List.of());
        when(gemini.generateContent(anyString(), anyString(), anyString())).thenReturn("Nothing to report.");

        assertEquals("Nothing to report.", service().askFollowUp("Anything odd?").answer());
    }

    @Test
    void askFollowUp_doesNotPersistAnything() {
        givenLatestCommentary("summary");
        when(ynab.getRecentAmexTransactions(30)).thenReturn(List.of());
        when(gemini.generateContent(anyString(), anyString(), anyString())).thenReturn("ok");

        service().askFollowUp("q");

        verify(repository).findDistinctCurrencies();
        verify(repository).findFirstByCurrencyOrderByTimestampDescIdDesc("GBP");
        verifyNoMoreInteractions(repository);
    }
}
