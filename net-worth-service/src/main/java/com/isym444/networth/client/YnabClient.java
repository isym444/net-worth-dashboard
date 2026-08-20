package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class YnabClient {

    private static final String BASE_URL = "https://api.ynab.com/v1";

    private final RestClient restClient = RestClient.create();

    public JsonNode fetchAccounts(String budgetId, String apiToken) {
        return restClient.get()
                .uri(BASE_URL + "/budgets/{budgetId}/accounts", budgetId)
                .header("Authorization", "Bearer " + apiToken)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode fetchBudgetSettings(String budgetId, String apiToken) {
        return restClient.get()
                .uri(BASE_URL + "/budgets/{budgetId}/settings", budgetId)
                .header("Authorization", "Bearer " + apiToken)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode fetchAccountTransactions(String budgetId, String accountId, String apiToken, String sinceDate) {
        return restClient.get()
                .uri(BASE_URL + "/budgets/{budgetId}/accounts/{accountId}/transactions?since_date={sinceDate}",
                        budgetId, accountId, sinceDate)
                .header("Authorization", "Bearer " + apiToken)
                .retrieve()
                .body(JsonNode.class);
    }
}
