package com.isym444.networth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isym444.networth.client.GeminiClient;
import com.isym444.networth.dto.AmexInsightsResponse;
import com.isym444.networth.dto.AmexTransaction;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Generates LLM commentary on recent Amex transactions and, on manual
 * refresh, persists it onto the latest snapshot row for every currency.
 */
@Service
public class AmexInsightService {

    private static final int LOOKBACK_DAYS = 30;

    private final YnabTransactionService ynabTransactionService;
    private final GeminiClient geminiClient;
    private final NetWorthSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${networth.llm.gemini.api-key}")
    private String apiKey;

    @Value("${networth.llm.gemini.model}")
    private String model;

    public AmexInsightService(YnabTransactionService ynabTransactionService,
                               GeminiClient geminiClient,
                               NetWorthSnapshotRepository repository,
                               ObjectMapper objectMapper) {
        this.ynabTransactionService = ynabTransactionService;
        this.geminiClient = geminiClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Generates fresh commentary without persisting it anywhere. */
    public String generateCommentary() {
        List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
        return geminiClient.generateContent(model, buildPrompt(transactions), apiKey);
    }

    /** Generates fresh commentary and writes it onto the latest snapshot row for every currency. */
    public AmexInsightsResponse refreshAndPersist() {
        List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
        String commentary = geminiClient.generateContent(model, buildPrompt(transactions), apiKey);

        for (String currency : repository.findDistinctCurrencies()) {
            repository.findFirstByCurrencyOrderByTimestampDescIdDesc(currency)
                    .ifPresent(row -> {
                        row.setAiCommentary(commentary);
                        repository.save(row);
                    });
        }

        return new AmexInsightsResponse(commentary, Instant.now(), transactions.size());
    }

    private String buildPrompt(List<AmexTransaction> transactions) {
        String json;
        try {
            json = objectMapper.writeValueAsString(transactions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize transactions for the LLM prompt", e);
        }
        return """
                You are a concise personal-finance analyst. Below is a JSON array of the user's recent
                Amex credit card transactions (last 30 days), each with date, payee, category, amount
                (in the account's currency, positive = money spent), memo, and account name.

                Respond in plain text only — no markdown headers, no asterisks, no numbered lists. Use
                short paragraphs and "- " for bullet points where useful. Keep the entire response under
                250 words.

                Cover:
                - A brief summary of overall spending and the top 2-3 categories by total spend.
                - Any notable, unusual, or one-off transactions worth flagging.
                - Any visible trend (e.g. increasing spend in a category, recurring charges).
                - 2-4 concrete, specific financial tips grounded in this actual data (not generic advice).

                If the transaction list is empty, say so briefly and skip the sections that don't apply.

                Transactions (JSON):
                """ + json;
    }
}
