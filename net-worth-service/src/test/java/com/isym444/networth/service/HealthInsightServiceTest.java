package com.isym444.networth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isym444.networth.client.GeminiClient;
import com.isym444.networth.client.HealthSnapshotData;
import com.isym444.networth.client.OpenWearablesClient;
import com.isym444.networth.dto.HealthInsightResponse;
import com.isym444.networth.model.HealthInsight;
import com.isym444.networth.repository.HealthInsightRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthInsightServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void generateCommentary_fetchesWearablesDataAndReturnsGeminiText() {
        OpenWearablesClient openWearablesClient = mock(OpenWearablesClient.class);
        GeminiClient geminiClient = mock(GeminiClient.class);
        HealthSnapshotData data = sampleSnapshotData(8432);
        when(openWearablesClient.fetchLast30Days("https://wearables.example.com", "user-1", "wearables-key"))
                .thenReturn(data);
        when(geminiClient.generateContent(eq("gemini-3.6-flash"), anyString(), eq("gemini-key")))
                .thenReturn("You walked a lot this month.");

        HealthInsightService service = newService(openWearablesClient, geminiClient, mock(HealthInsightRepository.class));

        String result = service.generateCommentary();

        assertEquals("You walked a lot this month.", result);
    }

    @Test
    void generateCommentary_includesActivityBodyAndHealthScoreDataInThePrompt() {
        OpenWearablesClient openWearablesClient = mock(OpenWearablesClient.class);
        GeminiClient geminiClient = mock(GeminiClient.class);
        // A distinctive step count that should only appear in the prompt if the activity
        // summary data actually got serialized into it.
        HealthSnapshotData data = sampleSnapshotData(11223);
        when(openWearablesClient.fetchLast30Days(anyString(), anyString(), anyString())).thenReturn(data);
        when(geminiClient.generateContent(anyString(), anyString(), anyString())).thenReturn("commentary");

        HealthInsightService service = newService(openWearablesClient, geminiClient, mock(HealthInsightRepository.class));
        service.generateCommentary();

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(geminiClient).generateContent(anyString(), promptCaptor.capture(), anyString());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("11223"), "prompt should include the activity summary data");
        assertTrue(prompt.contains("72.5"), "prompt should include the body summary data");
        assertTrue(prompt.contains("sleep"), "prompt should include the health score data");
    }

    @Test
    void refreshAndPersist_savesANewRowAndReturnsIt() {
        OpenWearablesClient openWearablesClient = mock(OpenWearablesClient.class);
        GeminiClient geminiClient = mock(GeminiClient.class);
        HealthInsightRepository repository = mock(HealthInsightRepository.class);
        when(openWearablesClient.fetchLast30Days(anyString(), anyString(), anyString()))
                .thenReturn(sampleSnapshotData(1000));
        when(geminiClient.generateContent(anyString(), anyString(), anyString())).thenReturn("Great progress!");
        when(repository.save(any(HealthInsight.class))).thenAnswer(invocation -> {
            HealthInsight saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        HealthInsightService service = newService(openWearablesClient, geminiClient, repository);

        HealthInsightResponse response = service.refreshAndPersist();

        assertEquals("Great progress!", response.commentary());
        verify(repository).save(any(HealthInsight.class));
    }

    @Test
    void getLatest_returnsEmpty_whenNoRowsExist() {
        HealthInsightRepository repository = mock(HealthInsightRepository.class);
        when(repository.findFirstByOrderByGeneratedAtDesc()).thenReturn(Optional.empty());
        HealthInsightService service =
                newService(mock(OpenWearablesClient.class), mock(GeminiClient.class), repository);

        assertTrue(service.getLatest().isEmpty());
    }

    @Test
    void getLatest_returnsTheLatestRowMappedToAResponse() {
        HealthInsightRepository repository = mock(HealthInsightRepository.class);
        HealthInsight row = new HealthInsight();
        row.setCommentary("Yesterday's insight");
        Instant generatedAt = Instant.parse("2026-08-22T07:00:00Z");
        row.setGeneratedAt(generatedAt);
        when(repository.findFirstByOrderByGeneratedAtDesc()).thenReturn(Optional.of(row));
        HealthInsightService service =
                newService(mock(OpenWearablesClient.class), mock(GeminiClient.class), repository);

        HealthInsightResponse response = service.getLatest().orElseThrow();

        assertEquals("Yesterday's insight", response.commentary());
        assertEquals(generatedAt, response.generatedAt());
    }

    @Test
    void scheduledHealthInsight_swallowsExceptions_soAFailureNeverBreaksTheScheduler() {
        OpenWearablesClient openWearablesClient = mock(OpenWearablesClient.class);
        when(openWearablesClient.fetchLast30Days(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Open Wearables is down"));
        HealthInsightService service =
                newService(openWearablesClient, mock(GeminiClient.class), mock(HealthInsightRepository.class));

        assertDoesNotThrow(service::scheduledHealthInsight);
    }

    @Test
    void scheduledHealthInsight_onSuccess_persistsARow() {
        OpenWearablesClient openWearablesClient = mock(OpenWearablesClient.class);
        GeminiClient geminiClient = mock(GeminiClient.class);
        HealthInsightRepository repository = mock(HealthInsightRepository.class);
        when(openWearablesClient.fetchLast30Days(anyString(), anyString(), anyString()))
                .thenReturn(sampleSnapshotData(1000));
        when(geminiClient.generateContent(anyString(), anyString(), anyString())).thenReturn("commentary");
        when(repository.save(any(HealthInsight.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HealthInsightService service = newService(openWearablesClient, geminiClient, repository);
        service.scheduledHealthInsight();

        verify(repository).save(any(HealthInsight.class));
    }

    private HealthInsightService newService(OpenWearablesClient openWearablesClient, GeminiClient geminiClient,
                                             HealthInsightRepository repository) {
        HealthInsightService service =
                new HealthInsightService(openWearablesClient, geminiClient, repository, OBJECT_MAPPER);
        ReflectionTestUtils.setField(service, "wearablesBaseUrl", "https://wearables.example.com");
        ReflectionTestUtils.setField(service, "wearablesApiKey", "wearables-key");
        ReflectionTestUtils.setField(service, "wearablesUserId", "user-1");
        ReflectionTestUtils.setField(service, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(service, "geminiModel", "gemini-3.6-flash");
        return service;
    }

    private HealthSnapshotData sampleSnapshotData(int steps) {
        JsonNode activity = parse("[{\"date\":\"2026-08-22\",\"steps\":" + steps + "}]");
        JsonNode healthScores = parse("[{\"category\":\"sleep\",\"value\":82}]");
        JsonNode body = parse("{\"slow_changing\":{\"weight_kg\":72.5}}");
        JsonNode workouts = parse("[]");
        return new HealthSnapshotData(activity, healthScores, body, workouts);
    }

    private JsonNode parse(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
