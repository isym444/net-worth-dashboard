package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class OpenWearablesClient {

    private static final Logger log = LoggerFactory.getLogger(OpenWearablesClient.class);

    private static final String API_KEY_HEADER = "X-Open-Wearables-API-Key";

    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    /** Fetches the last 30 days of activity + health-score data, plus the current body summary. */
    public HealthSnapshotData fetchLast30Days(String baseUrl, String userId, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Open Wearables is not configured (missing base URL) — set OPEN_WEARABLES_BASE_URL.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Open Wearables is not configured (missing API key) — set OPEN_WEARABLES_API_KEY.");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException(
                    "Open Wearables is not configured (missing user id) — set OPEN_WEARABLES_USER_ID.");
        }

        LocalDate endDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalDate startDate = endDate.minusDays(30);

        JsonNode activity = get(activityUri(baseUrl, userId, startDate, endDate), apiKey);
        JsonNode healthScores = get(healthScoresUri(baseUrl, userId, startDate, endDate), apiKey);
        JsonNode body = get(bodyUri(baseUrl, userId), apiKey);
        JsonNode workouts = get(workoutsUri(baseUrl, userId, startDate, endDate), apiKey);
        log.info("Workout details: {}", workouts);

        return new HealthSnapshotData(activity.path("data"), healthScores.path("data"), body, workouts.path("data"));
    }

    /**
     * baseUrl is the full API root Open Wearables exposes its paths under (e.g.
     * "https://your-instance/api/v1"), not just the origin — every path below is relative to
     * that root. Package-private so {@code OpenWearablesClientTest} can assert on it directly.
     */
    String activityUri(String baseUrl, String userId, LocalDate startDate, LocalDate endDate) {
        return baseUrl + "/users/" + userId + "/summaries/activity"
                + "?start_date=" + startDate + "&end_date=" + endDate + "&limit=400";
    }

    String healthScoresUri(String baseUrl, String userId, LocalDate startDate, LocalDate endDate) {
        return baseUrl + "/users/" + userId + "/health-scores"
                + "?start_date=" + startDate + "&end_date=" + endDate + "&limit=1000";
    }

    String bodyUri(String baseUrl, String userId) {
        return baseUrl + "/users/" + userId + "/summaries/body";
    }

    String workoutsUri(String baseUrl, String userId, LocalDate startDate, LocalDate endDate) {
        return baseUrl + "/users/" + userId + "/events/workouts"
                + "?start_date=" + startDate + "&end_date=" + endDate;
    }

    private JsonNode get(String uri, String apiKey) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(API_KEY_HEADER, apiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // The API key travels in a header here, not the URL, so it's safe to log the
            // exception directly (same reasoning as LocalLlmClient).
            log.error("Open Wearables request failed", e);
            throw new IllegalStateException("Open Wearables API request failed");
        }
    }
}
