package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class Trading212Client {

    private final RestClient restClient = RestClient.create();

    public JsonNode fetchAccountSummary(String baseUrl, String apiKey, String apiSecret) {
        String auth = buildAuthHeader(apiKey, apiSecret);
        return restClient.get()
                .uri(baseUrl + "/api/v0/equity/account/summary")
                .header("Authorization", auth)
                .retrieve()
                .body(JsonNode.class);
    }

    private String buildAuthHeader(String apiKey, String apiSecret) {
        if (apiSecret != null && !apiSecret.isBlank()) {
            String raw = apiKey + ":" + apiSecret;
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        return apiKey;
    }
}
