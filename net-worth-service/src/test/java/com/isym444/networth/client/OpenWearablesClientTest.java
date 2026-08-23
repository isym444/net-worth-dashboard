package com.isym444.networth.client;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * baseUrl is the full API root Open Wearables exposes its paths under (e.g.
 * ".../api/v1"), not just the origin — these tests pin that down after a real 404 caused
 * by the client hardcoding an extra "/api/v1" segment on top of a baseUrl that already
 * included one.
 */
class OpenWearablesClientTest {

    private final OpenWearablesClient client = new OpenWearablesClient();

    @Test
    void activityUri_doesNotDuplicateTheApiVersionSegmentAlreadyInBaseUrl() {
        String uri = client.activityUri("https://wearables.example.com/api/v1", "user-1",
                LocalDate.parse("2026-07-24"), LocalDate.parse("2026-08-23"));

        assertEquals("https://wearables.example.com/api/v1/users/user-1/summaries/activity"
                + "?start_date=2026-07-24&end_date=2026-08-23&limit=400", uri);
    }

    @Test
    void healthScoresUri_doesNotDuplicateTheApiVersionSegmentAlreadyInBaseUrl() {
        String uri = client.healthScoresUri("https://wearables.example.com/api/v1", "user-1",
                LocalDate.parse("2026-07-24"), LocalDate.parse("2026-08-23"));

        assertEquals("https://wearables.example.com/api/v1/users/user-1/health-scores"
                + "?start_date=2026-07-24&end_date=2026-08-23&limit=1000", uri);
    }

    @Test
    void bodyUri_doesNotDuplicateTheApiVersionSegmentAlreadyInBaseUrl() {
        String uri = client.bodyUri("https://wearables.example.com/api/v1", "user-1");

        assertEquals("https://wearables.example.com/api/v1/users/user-1/summaries/body", uri);
    }
}
