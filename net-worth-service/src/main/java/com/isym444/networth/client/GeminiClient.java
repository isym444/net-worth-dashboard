package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    // Google's newer "Interactions API", which auth-type API keys (the default for
    // keys created in AI Studio since 2026) require — the older
    // /v1beta/models/{model}:generateContent?key=... endpoint 404s for these keys.
    private static final String URI = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String API_REVISION = "2026-05-20";

    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();

    // The Interactions API runs internal "thought" reasoning steps before producing output,
    // which can genuinely take close to (or over) 30s for larger prompts — hence both the
    // longer-than-usual read timeout and the retry loop below for when it tips over that.
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(3);

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(8));
        factory.setReadTimeout(Duration.ofSeconds(200));
        return factory;
    }

    public String generateContent(String model, String prompt, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini is not configured (missing API key) — set GEMINI_API_KEY.");
        }

        Map<String, Object> body = Map.of("model", model, "input", prompt);

        JsonNode response = null;
        RestClientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                response = restClient.post()
                        .uri(URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-goog-api-key", apiKey)
                        .header("Api-Revision", API_REVISION)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                lastError = null;
                break;
            } catch (RestClientException e) {
                lastError = e;
                boolean retryable = isRetryable(e);
                // The API key travels in a header here, not the URL, so — unlike the old
                // generateContent endpoint — the exception message is safe to log directly.
                log.warn("Gemini request failed (attempt {}/{}, retryable={})", attempt, MAX_ATTEMPTS, retryable, e);
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    break;
                }
                sleepBeforeRetry(INITIAL_BACKOFF.multipliedBy(1L << (attempt - 1)));
            }
        }

        if (lastError != null) {
            log.error("Gemini request failed after {} attempt(s)", MAX_ATTEMPTS, lastError);
            String detail = (lastError instanceof RestClientResponseException re)
                    ? "HTTP " + re.getStatusCode().value()
                    : "network/timeout error";
            throw new IllegalStateException("Gemini API request failed (" + detail + ")");
        }

        String text = extractText(response);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini API returned no usable content");
        }
        return text.trim();
    }

    /** Retries on timeouts/network errors and 5xx/429 responses; not on 4xx (retrying won't help those). */
    private static boolean isRetryable(RestClientException e) {
        if (e instanceof RestClientResponseException re) {
            return re.getStatusCode().is5xxServerError() || re.getStatusCode().value() == 429;
        }
        return true;
    }

    private static void sleepBeforeRetry(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Gemini request", ie);
        }
    }

    /**
     * The Interactions API returns a "steps" array mixing internal "thought" steps
     * (opaque reasoning signatures, no text) with "model_output" steps (the actual
     * answer, as one or more "text"-typed content items). Concatenates every text
     * content item across every model_output step, skipping anything else.
     */
    private String extractText(JsonNode response) {
        if (response == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText(null))) {
                continue;
            }
            for (JsonNode contentItem : step.path("content")) {
                if ("text".equals(contentItem.path("type").asText(null))) {
                    result.append(contentItem.path("text").asText(""));
                }
            }
        }
        return result.length() > 0 ? result.toString() : null;
    }
}
