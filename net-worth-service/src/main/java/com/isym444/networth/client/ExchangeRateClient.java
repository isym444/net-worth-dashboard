package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class ExchangeRateClient {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateClient.class);

    private static final String BASE_URL = "https://api.fxratesapi.com/latest";

    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    public JsonNode fetchRates(String baseCurrency, String targetCurrencies, String apiKey) {
        String uri = BASE_URL
                + "?base=" + baseCurrency
                + "&currencies=" + targetCurrencies
                + "&resolution=1m&amount=1&places=6&format=json"
                + "&api_key=" + apiKey;

        JsonNode response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // Do not log/rethrow the original exception or its message: it typically
            // embeds the full request URI (including the api_key query parameter), and
            // standard SLF4J/Logback exception logging would print that verbatim.
            log.error("Exchange rate request failed (details suppressed to avoid logging credential in URL)");
            throw new IllegalStateException("Exchange rate API request failed");
        }

        if (response == null || !response.path("success").asBoolean(false)) {
            String error = response == null ? "empty response" : response.toString();
            throw new IllegalStateException("Exchange rate API error: " + error);
        }
        return response;
    }
}
