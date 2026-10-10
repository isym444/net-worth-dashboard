package com.isym444.networth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isym444.networth.client.LocalLlmClient;
import com.isym444.networth.dto.AmexFollowUpResponse;
import com.isym444.networth.dto.AmexInsightsResponse;
import com.isym444.networth.dto.AmexTransaction;
import com.isym444.networth.repository.NetWorthSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Generates LLM commentary on recent Amex transactions and, on manual
 * refresh, persists it onto the latest snapshot row for every currency.
 */
@Service
public class AmexInsightService {

    private static final Logger log = LoggerFactory.getLogger(AmexInsightService.class);

    private static final int LOOKBACK_DAYS = 30;
    private static final String CURRENCY_SYMBOL = "£";
    private static final String UNASSIGNED_CATEGORY = "Other (uncategorised)";

    private final YnabTransactionService ynabTransactionService;
    private final LocalLlmClient llmClient;
    private final NetWorthSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${networth.llm.model}")
    private String model;

    public AmexInsightService(YnabTransactionService ynabTransactionService,
                               LocalLlmClient llmClient,
                               NetWorthSnapshotRepository repository,
                               ObjectMapper objectMapper) {
        this.ynabTransactionService = ynabTransactionService;
        this.llmClient = llmClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Generates fresh commentary without persisting it anywhere. */
    public String generateCommentary() {
        List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
        return generateInsight(transactions);
    }

    /**
     * Two-step generation so the numbers are never the model's arithmetic: the LLM only assigns
     * each transaction to a category, the totals and breakdown are computed here (and always
     * reconcile to the exact total), then the LLM writes the commentary around those figures.
     */
    private String generateInsight(List<AmexTransaction> transactions) {
        if (transactions.isEmpty()) {
            return llmClient.generateContent(model, buildPrompt(transactions, null));
        }

        BigDecimal total = transactions.stream()
                .map(t -> amountSpent(t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, List<Integer>> assignment;
        try {
            String reply = llmClient.generateContent(model, buildCategorisationPrompt(transactions));
            assignment = parseCategorisation(reply, transactions.size());
        } catch (IllegalArgumentException | JsonProcessingException e) {
            log.warn("Could not parse LLM categorisation ({}); falling back to single-step commentary", e.getMessage());
            return llmClient.generateContent(model, buildPrompt(transactions, total));
        }

        String header = "Total spending (last " + LOOKBACK_DAYS + " days): " + money(total)
                + " across " + transactions.size() + " transactions";
        String breakdown = renderBreakdown(transactions, assignment);
        String narrative = llmClient.generateContent(
                model, buildNarrativePrompt(transactions, header, breakdown));
        return header + "\n\nBreakdown:\n" + breakdown + "\n\n" + narrative;
    }

    private static BigDecimal amountSpent(AmexTransaction t) {
        // YNAB outflows are negative; "spent" is positive. Refunds come out negative.
        return BigDecimal.valueOf(-t.amount()).setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal amount) {
        return CURRENCY_SYMBOL + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Parses {"categories":[{"name":"...","transactionIds":[0,1]}]} out of the model's reply. */
    Map<String, List<Integer>> parseCategorisation(String reply, int transactionCount) throws JsonProcessingException {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object in categorisation reply");
        }
        JsonNode categories = objectMapper.readTree(reply.substring(start, end + 1)).path("categories");
        if (!categories.isArray() || categories.isEmpty()) {
            throw new IllegalArgumentException("categorisation reply has no categories");
        }

        Map<String, List<Integer>> assignment = new LinkedHashMap<>();
        TreeSet<Integer> seen = new TreeSet<>();
        for (JsonNode category : categories) {
            String name = category.path("name").asText("").trim();
            if (name.isEmpty()) {
                name = UNASSIGNED_CATEGORY;
            }
            for (JsonNode idNode : category.path("transactionIds")) {
                int id = idNode.asInt(-1);
                // Ignore out-of-range ids and transactions the model listed twice (first wins).
                if (id >= 0 && id < transactionCount && seen.add(id)) {
                    assignment.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
                }
            }
        }
        // Anything the model forgot still has to be counted, or the breakdown won't add up.
        for (int id = 0; id < transactionCount; id++) {
            if (!seen.contains(id)) {
                assignment.computeIfAbsent(UNASSIGNED_CATEGORY, k -> new ArrayList<>()).add(id);
            }
        }
        return assignment;
    }

    String renderBreakdown(List<AmexTransaction> transactions, Map<String, List<Integer>> assignment) {
        record Line(String name, BigDecimal total) {}
        List<Line> lines = new ArrayList<>();
        assignment.forEach((name, ids) -> lines.add(new Line(name, ids.stream()
                .map(id -> amountSpent(transactions.get(id)))
                .reduce(BigDecimal.ZERO, BigDecimal::add))));
        lines.sort(Comparator.comparing(Line::total).reversed());

        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(line.name()).append(": ").append(money(line.total()));
        }
        return sb.toString();
    }

    private String buildCategorisationPrompt(List<AmexTransaction> transactions) {
        List<IndexedTransaction> indexed = new ArrayList<>();
        for (int i = 0; i < transactions.size(); i++) {
            indexed.add(IndexedTransaction.from(i, transactions.get(i)));
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(indexed);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize transactions for the LLM prompt", e);
        }
        return """
                You are categorising a user's recent Amex credit card transactions. Below is a JSON array;
                each transaction has an "id", date, payee, category, amount (positive = money spent), memo
                and account name.

                Assign EVERY transaction to exactly one category. Do not do any arithmetic.

                Category naming rules:
                - Make the category names as informative as possible so the user can recall what each
                  included. Where useful add a short detail in brackets, e.g. "Events & Live Entertainment
                  (Classical Music)".
                - Musiq Group is grand piano rental, so give it its own category (named "Grand Piano Rental").
                - Avoid vague names like "Treats" or "Shopping & Misc"; prefer specific ones such as
                  "Dining Out", "Groceries", "Cloud & Dev Infrastructure", "Subscriptions & Software".
                - Use roughly 5-10 categories, merging tiny ones into the closest fit.

                Reply with ONLY a JSON object, no prose and no code fences, in exactly this shape:
                {"categories":[{"name":"Groceries","transactionIds":[0,3,7]}]}

                Transactions (JSON):
                """ + json;
    }

    private String buildNarrativePrompt(List<AmexTransaction> transactions, String header, String breakdown) {
        return """
                You are a concise personal-finance analyst. Below are the user's exact spending figures for
                the last 30 days on their Amex credit card (already computed and correct — do not
                recalculate, re-add or contradict them), followed by the underlying transactions as a JSON
                array (date, payee, category, amount where positive = money spent, memo, account name).

                """ + header + """


                Breakdown by category:
                """ + breakdown + """


                Write the commentary that goes UNDER this breakdown. Do not repeat the breakdown or the
                total. Respond in plain text only — no markdown headers, no asterisks, no numbered lists.
                Use short paragraphs and "- " for bullet points where useful. Keep it under 200 words.

                Cover:
                - A brief summary of the top 2-3 categories by total spend, using the category figures above.
                - Any notable, unusual, or one-off transactions worth flagging (quote individual
                  transaction amounts exactly as they appear; never quote a sum you computed yourself).
                - Any visible trend (e.g. increasing spend in a category, recurring charges). Count carefully.
                - 2-4 concrete, specific financial tips grounded in this actual data (not generic advice).

                Important:
                - Don't comment on the fact that transactions are logged without category or ready to assign.
                - Don't comment specifically on starting balances.
                - Don't recommend cancelling subscriptions. I track these separately and know I should probably cancel some but find them too useful.
                - Don't tell me off for spending as long as it is within my spending targets i.e. around 20 gbp per day or around 600 gbp per month. On the contrary, give me positive reinforcement if I'm keeping within my monthly spending target

                Transactions (JSON):
                """ + transactionsJson(transactions);
    }

    /** Generates fresh commentary and writes it onto the latest snapshot row for every currency. */
    public AmexInsightsResponse refreshAndPersist() {
        List<AmexTransaction> transactions = ynabTransactionService.getRecentAmexTransactions(LOOKBACK_DAYS);
        String commentary = generateInsight(transactions);

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
        String answer = llmClient.generateContent(
                model, buildFollowUpPrompt(transactions, latestCommentary(), question));
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

    private String buildPrompt(List<AmexTransaction> transactions, BigDecimal exactTotal) {
        String json = transactionsJson(transactions);
        String totalLine = exactTotal == null ? "" : "\nThe exact total spending is " + money(exactTotal)
                + " — your breakdown MUST add up to exactly this figure.\n";
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
                    - Note that Musiq Group is grand piano rental. So make that its own cateogry rather than putting it in "Events & live entertainment"
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
                """ + totalLine + """

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
    private record IndexedTransaction(int id, LocalDate date, String payeeName, String categoryName,
                                      double amount, String memo, String accountName) {
        static IndexedTransaction from(int id, AmexTransaction t) {
            return new IndexedTransaction(id, t.date(), t.payeeName(), t.categoryName(),
                    -t.amount(), t.memo(), t.accountName());
        }
    }

    private record PromptTransaction(LocalDate date, String payeeName, String categoryName,
                                      double amount, String memo, String accountName) {
        static PromptTransaction from(AmexTransaction t) {
            return new PromptTransaction(t.date(), t.payeeName(), t.categoryName(),
                    -t.amount(), t.memo(), t.accountName());
        }
    }
}
