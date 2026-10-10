package com.isym444.networth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.isym444.networth.client.LocalLlmClient;
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
    private final LocalLlmClient llm = mock(LocalLlmClient.class);
    private final NetWorthSnapshotRepository repository = mock(NetWorthSnapshotRepository.class);

    private AmexInsightService service() {
        AmexInsightService service = new AmexInsightService(
                ynab, llm, repository, new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(service, "model", "llm-model");
        return service;
    }

    private void givenLatestCommentary(String commentary) {
        NetWorthSnapshot row = new NetWorthSnapshot();
        row.setAiCommentary(commentary);
        when(repository.findDistinctCurrencies()).thenReturn(List.of("GBP"));
        when(repository.findFirstByCurrencyOrderByTimestampDescIdDesc("GBP")).thenReturn(Optional.of(row));
    }

    @Test
    void askFollowUp_sendsTheQuestionTheLatestSummaryAndTheTransactionsToTheLlm() {
        givenLatestCommentary("Events & Live Entertainment: £198.00");
        when(ynab.getRecentAmexTransactions(30)).thenReturn(List.of(
                new AmexTransaction(LocalDate.of(2026, 9, 12), "Royal Albert Hall", "Fun", -120.0, "proms", "Amex")));
        when(llm.generateContent(eq("llm-model"), anyString())).thenReturn("A Proms concert.");

        AmexFollowUpResponse response = service().askFollowUp("What were the events?");

        assertEquals("A Proms concert.", response.answer());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).generateContent(eq("llm-model"), prompt.capture());
        assertTrue(prompt.getValue().contains("What were the events?"));
        assertTrue(prompt.getValue().contains("Events & Live Entertainment: £198.00"));
        assertTrue(prompt.getValue().contains("Royal Albert Hall"));
        assertTrue(prompt.getValue().contains("\"amount\":120.0"), "amounts use the prompt's positive-means-spent convention");
    }

    @Test
    void askFollowUp_worksWithoutAStoredSummary() {
        when(repository.findDistinctCurrencies()).thenReturn(List.of());
        when(ynab.getRecentAmexTransactions(30)).thenReturn(List.of());
        when(llm.generateContent(anyString(), anyString())).thenReturn("Nothing to report.");

        assertEquals("Nothing to report.", service().askFollowUp("Anything odd?").answer());
    }

    @Test
    void askFollowUp_doesNotPersistAnything() {
        givenLatestCommentary("summary");
        when(ynab.getRecentAmexTransactions(30)).thenReturn(List.of());
        when(llm.generateContent(anyString(), anyString())).thenReturn("ok");

        service().askFollowUp("q");

        verify(repository).findDistinctCurrencies();
        verify(repository).findFirstByCurrencyOrderByTimestampDescIdDesc("GBP");
        verifyNoMoreInteractions(repository);
    }

    private static List<AmexTransaction> threeTransactions() {
        return List.of(
                new AmexTransaction(LocalDate.of(2026, 9, 1), "Musiq Group", "Fun", -198.0, "", "Amex"),
                new AmexTransaction(LocalDate.of(2026, 9, 2), "Tesco", "Food", -60.10, "", "Amex"),
                new AmexTransaction(LocalDate.of(2026, 9, 3), "Lidl", "Food", -51.03, "", "Amex"));
    }

    @Test
    void generateCommentary_computesTotalsInCodeAndAddsNarrativeUnderneath() {
        when(ynab.getRecentAmexTransactions(30)).thenReturn(threeTransactions());
        when(llm.generateContent(anyString(), anyString())).thenReturn(
                "```json\n{\"categories\":[{\"name\":\"Grand Piano Rental\",\"transactionIds\":[0]},"
                        + "{\"name\":\"Groceries\",\"transactionIds\":[1,2]}]}\n```",
                "You are well within target.");

        String commentary = service().generateCommentary();

        assertTrue(commentary.startsWith("Total spending (last 30 days): £309.13 across 3 transactions"), commentary);
        assertTrue(commentary.contains("- Grand Piano Rental: £198.00\n- Groceries: £111.13"), commentary);
        assertTrue(commentary.endsWith("You are well within target."), commentary);
    }

    @Test
    void generateCommentary_countsTransactionsTheModelForgotOrListedTwice() {
        when(ynab.getRecentAmexTransactions(30)).thenReturn(threeTransactions());
        when(llm.generateContent(anyString(), anyString())).thenReturn(
                "{\"categories\":[{\"name\":\"Groceries\",\"transactionIds\":[1,2,1,99]}]}",
                "narrative");

        String commentary = service().generateCommentary();

        // id 0 was never assigned, 1 was listed twice, 99 doesn't exist
        assertTrue(commentary.contains("- Other (uncategorised): £198.00"), commentary);
        assertTrue(commentary.contains("- Groceries: £111.13"), commentary);
        assertTrue(commentary.startsWith("Total spending (last 30 days): £309.13"), commentary);
    }

    @Test
    void generateCommentary_fallsBackToSingleStepPromptWithTheExactTotalWhenCategorisationIsUnparseable() {
        when(ynab.getRecentAmexTransactions(30)).thenReturn(threeTransactions());
        when(llm.generateContent(anyString(), anyString())).thenReturn("I cannot do that", "fallback commentary");

        String commentary = service().generateCommentary();

        assertEquals("fallback commentary", commentary);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, org.mockito.Mockito.times(2)).generateContent(anyString(), prompt.capture());
        assertTrue(prompt.getAllValues().get(1).contains("exact total spending is £309.13"));
    }
}
