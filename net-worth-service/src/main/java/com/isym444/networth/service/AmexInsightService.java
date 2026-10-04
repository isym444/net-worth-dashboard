package com.isym444.networth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isym444.networth.client.GeminiClient;
import com.isym444.networth.dto.AmexFollowUpResponse;
import com.isym444.networth.dto.AmexInsightsResponse;
import com.isym444.networth.dto.AmexTransaction;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Answers a free-text follow-up about the spending insights (e.g. "what were the events and live
     * entertainment?") from the same last-30-days transactions, with the latest stored commentary as
     * context so the question can refer to its categories. Nothing is persisted.
     */
    public AmexFollowUpResponse askFollowUp(String question) {
        List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
        String answer = geminiClient.generateContent(
                model, buildFollowUpPrompt(transactions, latestCommentary(), question), apiKey);
        return new AmexFollowUpResponse(answer);
    }

    private String latestCommentary() {
        for (String currency : repository.findDistinctCurrencies()) {
            String commentary = repository.findFirstByCurrencyOrderByTimestampDescIdDesc(currency)
                    .map(row -> row.getAiCommentary())
                    .orElse(null);
            if (commentary != null && !commentary.isBlank()) {
                return commentary;
            }
        }
        return null;
    }

    String buildFollowUpPrompt(List<AmexTransaction> transactions, String commentary, String question) {
        String earlier = commentary == null
                ? "(none yet)"
                : commentary;
        return """
                You are a concise personal-finance analyst answering a follow-up question about the user's
                recent Amex credit card spending. Below are an earlier spending summary you gave them and
                the JSON array of their Amex transactions for the last 30 days (each with date, payee,
                category, amount in the account's currency where positive = money spent, memo, and
                account name).

                Answer the question using only these transactions. When asked what a category or
                figure consisted of, list the specific transactions behind it (date, payee, amount) and
                check that they add up to the figure in the summary. If the transactions don't contain
                enough to answer, say so rather than guessing.

                Respond in plain text only — no markdown headers, no asterisks. Use "- " for bullet
                points. Keep the response under 200 words.

                Earlier summary:
                """ + earlier + """


                Transactions (JSON):
                """ + transactionsJson(transactions) + """


                Question:
                """ + question;
    }

    private String transactionsJson(List<AmexTransaction> transactions) {
        try {
            return objectMapper.writeValueAsString(transactions.stream().map(PromptTransaction::from).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize transactions for the LLM prompt", e);
        }
    }

    private String buildPrompt(List<AmexTransaction> transactions) {
        String json = transactionsJson(transactions);
        return """
                You are a concise personal-finance analyst. Below is a JSON array of the user's recent
                Amex credit card transactions (last 30 days), each with date, payee, category, amount
                (in the account's currency, positive = money spent), memo, and account name.

                Respond in plain text only — no markdown headers, no asterisks, no numbered lists. Use
                short paragraphs and "- " for bullet points where useful. Keep the entire response under
                250 words.

                Cover:
                - A comprehensive breakdown of the last 30 days' spending where the breakdown MUST add up to total spending
                    - In the comprehensive breakdown, make the categories as imformative as possible so I can recall what the category included
                    - For example when saying Dining Out & Treats, dining out is fine but treats is too general
                    - Similarly, you previously gave 2 categories as "Dining Out & Treats" and "Shopping & Misc". Here it is too hard to pinpoint the difference between Treats and Shopping
                    - If you make a category like events & live entertainment, in brackets specify a more detailed breakdown e.g. Classical Music
                - A brief summary of the top 2-3 categories by total spend.
                - Any notable, unusual, or one-off transactions worth flagging.
                - Any visible trend (e.g. increasing spend in a category, recurring charges).
                - 2-4 concrete, specific financial tips grounded in this actual data (not generic advice).
                
                Important:
                - Don't comment on the fact that transactions are logged without category or ready to assign.
                - Don't comment specifically on starting balances.
                - Don't recommend cancelling subscriptions. I track these separately and know I should probably cancel some but find them too useful.
                - Don't tell me off for spending as long as it is within my spending targets i.e. around 20 gbp per day or around 600 gbp per month. On the contrary, give me positive reinforcement if I'm keeping within my monthly spending target

                If the transaction list is empty, say so briefly and skip the sections that don't apply.

                Transactions (JSON):
                """ + json;
    }

    /**
     * Mirrors {@link AmexTransaction} but negates the amount so it matches the sign convention
     * described in the prompt text (positive = money spent). YNAB itself represents outflows as
     * negative amounts, and {@link AmexTransaction} preserves that raw YNAB sign for its other
     * consumers (e.g. the {@code /api/v1/amex/transactions} endpoint), so the negation is applied
     * only here, at the point where the data is serialized for the LLM prompt.
     */
    private record PromptTransaction(LocalDate date, String payeeName, String categoryName,
                                      double amount, String memo, String accountName) {
        static PromptTransaction from(AmexTransaction t) {
            return new PromptTransaction(t.date(), t.payeeName(), t.categoryName(),
                    -t.amount(), t.memo(), t.accountName());
        }
    }
}
