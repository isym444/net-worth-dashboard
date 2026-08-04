package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExchangeRateClient {

    private static final String BASE_URL = "https://api.fxratesapi.com/latest";

    private final RestClient restClient = RestClient.create();

    public JsonNode fetchRates(String baseCurrency, String targetCurrencies, String apiKey) {
        String uri = BASE_URL
                + "?base=" + baseCurrency
                + "&currencies=" + targetCurrencies
                + "&resolution=1m&amount=1&places=6&format=json"
                + "&api_key=" + apiKey;

        JsonNode response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("success").asBoolean(false)) {
            String error = response == null ? "empty response" : response.toString();
            throw new IllegalStateException("Exchange rate API error: " + error);
        }
        return response;
    }
}
