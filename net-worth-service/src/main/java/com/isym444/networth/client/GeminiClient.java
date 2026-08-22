package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    public String generateContent(String model, String prompt, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini is not configured (missing API key) — set GEMINI_API_KEY.");
        }

        String uri = BASE_URL + model + ":generateContent?key=" + apiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // Do not log/rethrow the original exception or its message: it typically
            // embeds the full request URI (including the API key query parameter), and
            // standard SLF4J/Logback exception logging would print that verbatim.
            log.error("Gemini request failed (details suppressed to avoid logging credential in URL)");
            throw new IllegalStateException("Gemini API request failed");
        }

        String text = response == null
                ? null
                : response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini API returned no usable content");
        }
        return text.trim();
    }
}
