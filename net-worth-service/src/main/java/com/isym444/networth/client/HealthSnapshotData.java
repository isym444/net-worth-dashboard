package com.isym444.networth.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Combined result of the three Open Wearables calls {@link OpenWearablesClient} makes for a
 * single 30-day fetch: the activity summary and health-score arrays (each already unwrapped
 * from their {@code data} envelope), plus the body summary object (null if the API returned
 * no body data for the user).
 */
public record HealthSnapshotData(JsonNode activitySummaries, JsonNode healthScores, JsonNode bodySummary) {
}
