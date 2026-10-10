package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls a locally hosted LLM through the OpenAI-compatible {@code /v1/chat/completions} endpoint
 * (served by Ollama, LM Studio, llama.cpp server, vLLM, etc.).
 */
@Component
public class LocalLlmClient {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    // Local models can be slow, especially on first load, so allow a generous read timeout
    // and retry on timeouts/5xx.
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(3);

    @Value("${networth.llm.base-url}")
    private String baseUrl;

    // Optional: only needed if the local server sits behind a bearer-token proxy.
    @Value("${networth.llm.api-key:}")
    private String apiKey;

    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(8));
        factory.setReadTimeout(Duration.ofSeconds(300));
        return factory;
    }

    public String generateContent(String model, String prompt) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Local LLM is not configured (missing base URL) — set LLM_BASE_URL.");
        }
        // Accept the base URL with or without a trailing /v1 (e.g. http://host:11434 or http://host:11434/v1).
        String uri = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "") + CHAT_COMPLETIONS_PATH;

        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(Map.of("role", "user", "content", prompt)));

        JsonNode response = null;
        RestClientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                RestClient.RequestBodySpec request = restClient.post()
                        .uri(uri)
                        .contentType(MediaType.APPLICATION_JSON);
                if (apiKey != null && !apiKey.isBlank()) {
                    request = request.header("Authorization", "Bearer " + apiKey);
                }
                response = request.body(body).retrieve().body(JsonNode.class);
                lastError = null;
                break;
            } catch (RestClientException e) {
                lastError = e;
                boolean retryable = isRetryable(e);
                log.warn("Local LLM request failed (attempt {}/{}, retryable={})", attempt, MAX_ATTEMPTS, retryable, e);
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    break;
                }
                sleepBeforeRetry(INITIAL_BACKOFF.multipliedBy(1L << (attempt - 1)));
            }
        }

        if (lastError != null) {
            log.error("Local LLM request failed after {} attempt(s)", MAX_ATTEMPTS, lastError);
            String detail = (lastError instanceof RestClientResponseException re)
                    ? "HTTP " + re.getStatusCode().value()
                    : "network/timeout error";
            throw new IllegalStateException("Local LLM request failed (" + detail + ")");
        }

        String text = response == null ? null
                : response.path("choices").path(0).path("message").path("content").asText(null);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Local LLM returned no usable content");
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
            throw new IllegalStateException("Interrupted while waiting to retry local LLM request", ie);
        }
    }
}
