package com.isym444.networth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isym444.networth.client.GeminiClient;
import com.isym444.networth.client.HealthSnapshotData;
import com.isym444.networth.client.OpenWearablesClient;
import com.isym444.networth.dto.HealthInsightResponse;
import com.isym444.networth.model.HealthInsight;
import com.isym444.networth.repository.HealthInsightRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Generates LLM commentary on the last 30 days of Open Wearables health data. Unlike
 * {@link AmexInsightService}, this has no manual regenerate-vs-carry-forward split at the
 * snapshot level — the only writer is the daily cron ({@link #scheduledHealthInsight()}) and
 * the dashboard's own "Refresh insights" button ({@link #refreshAndPersist()} via the
 * controller), both of which always call Gemini fresh. The "Take snapshot now" button never
 * touches this service.
 */
@Service
public class HealthInsightService {

    private static final Logger log = LoggerFactory.getLogger(HealthInsightService.class);

    private final OpenWearablesClient openWearablesClient;
    private final GeminiClient geminiClient;
    private final HealthInsightRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${networth.wearables.base-url}")
    private String wearablesBaseUrl;

    @Value("${networth.wearables.api-key}")
    private String wearablesApiKey;

    @Value("${networth.wearables.user-id}")
    private String wearablesUserId;

    @Value("${networth.llm.gemini.api-key}")
    private String geminiApiKey;

    @Value("${networth.llm.gemini.model}")
    private String geminiModel;

    public HealthInsightService(OpenWearablesClient openWearablesClient,
                                 GeminiClient geminiClient,
                                 HealthInsightRepository repository,
                                 ObjectMapper objectMapper) {
        this.openWearablesClient = openWearablesClient;
        this.geminiClient = geminiClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${networth.health.insight.cron}", zone = "${networth.health.insight.zone}")
    public void scheduledHealthInsight() {
        log.info("Running scheduled health insight generation");
        try {
            refreshAndPersist();
            log.info("Health insight generated and persisted");
        } catch (Exception e) {
            log.error("Scheduled health insight generation failed", e);
        }
    }

    /** Generates fresh commentary without persisting it anywhere. */
    public String generateCommentary() {
        HealthSnapshotData data = openWearablesClient.fetchLast30Days(wearablesBaseUrl, wearablesUserId, wearablesApiKey);
        log.info("Raw open wearables data: ", data);
        return geminiClient.generateContent(geminiModel, buildPrompt(data), geminiApiKey);
    }

    /** Generates fresh commentary and persists it as a new row. */
    public HealthInsightResponse refreshAndPersist() {
        String commentary = generateCommentary();

        HealthInsight insight = new HealthInsight();
        insight.setGeneratedAt(Instant.now());
        insight.setCommentary(commentary);
        HealthInsight saved = repository.save(insight);

        return toResponse(saved);
    }

    /** Returns whatever the daily cron (or a manual refresh) last cached — no LLM call. */
    public Optional<HealthInsightResponse> getLatest() {
        return repository.findFirstByOrderByGeneratedAtDesc().map(this::toResponse);
    }

    private HealthInsightResponse toResponse(HealthInsight insight) {
        return new HealthInsightResponse(insight.getCommentary(), insight.getGeneratedAt());
    }

    private String buildPrompt(HealthSnapshotData data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize health data for the LLM prompt", e);
        }
        return """
                You are a concise personal health analyst. Below is JSON with the user's last 30 days of
                wearable-tracked health data: "activitySummaries" (daily steps/distance/calories/active
                minutes), "healthScores" (daily sleep/recovery/readiness scores from their wearable),
                "bodySummary" (their most recent weight/body composition/vitals), and "workouts" (logged
                exercise sessions with type, duration, and intensity where available).

                Respond in plain text only — no markdown headers, no asterisks, no numbered lists. Use
                short paragraphs and "- " for bullet points where useful. Keep the entire response under
                150 words.

                Cover:
                - A brief summary of overall activity and recovery/sleep trends over the period.
                - Any notable pattern worth flagging (e.g. declining sleep score, low activity days).
                - 2-4 concrete, specific suggestions grounded in this actual data (not generic advice).

                If a field is missing or empty, don't comment on its absence — just work with what's there.

                Health data (JSON):
                """ + json;
    }
}
