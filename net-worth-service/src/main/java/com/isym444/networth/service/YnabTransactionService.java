package com.isym444.networth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.isym444.networth.client.YnabClient;
import com.isym444.networth.dto.AmexTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads recent transactions straight from YNAB for dashboard display — unlike
 * NetWorthSnapshotService, this never persists anything.
 */
@Service
public class YnabTransactionService {

    private static final Logger log = LoggerFactory.getLogger(YnabTransactionService.class);

    private final YnabClient ynabClient;

    @Value("${networth.ynab.api-token}")
    private String ynabApiToken;

    @Value("${networth.ynab.budget-id}")
    private String ynabBudgetId;

    @Value("${networth.ynab.amex-account-ids}")
    private String ynabAmexAccountIds;

    public YnabTransactionService(YnabClient ynabClient) {
        this.ynabClient = ynabClient;
    }

    public List<AmexTransaction> getRecentAmexTransactions(int days) {
        if (ynabApiToken == null || ynabApiToken.isBlank()) {
            throw new IllegalStateException(
                    "YNAB is not configured (missing API token) — set YNAB_API_TOKEN.");
        }

        Set<String> amexAccountIds = parseIdSet(ynabAmexAccountIds);
        LocalDate sinceDate = LocalDate.now().minusDays(days);
        List<AmexTransaction> transactions = new ArrayList<>();

        JsonNode accounts = ynabClient.fetchAccounts(ynabBudgetId, ynabApiToken).path("data").path("accounts");
        for (JsonNode account : accounts) {
            if (account.path("deleted").asBoolean(false) || account.path("closed").asBoolean(false)) {
                continue;
            }
            String accountId = account.path("id").asText();
            if (!amexAccountIds.contains(accountId)) {
                continue;
            }
            String accountName = account.path("name").asText("");
            log.info("Fetching YNAB transactions for Amex account '{}' since {}", accountName, sinceDate);
            JsonNode result = ynabClient.fetchAccountTransactions(ynabBudgetId, accountId, ynabApiToken, sinceDate.toString());
            for (JsonNode transaction : result.path("data").path("transactions")) {
                if (transaction.path("deleted").asBoolean(false)) {
                    continue;
                }
                transactions.add(new AmexTransaction(
                        LocalDate.parse(transaction.path("date").asText()),
                        transaction.path("payee_name").asText(null),
                        transaction.path("category_name").asText(null),
                        transaction.path("amount").asLong(0) / 1000.0,
                        transaction.path("memo").asText(null),
                        accountName));
            }
        }

        transactions.sort(Comparator.comparing(AmexTransaction::date).reversed());
        return transactions;
    }

    private Set<String> parseIdSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
