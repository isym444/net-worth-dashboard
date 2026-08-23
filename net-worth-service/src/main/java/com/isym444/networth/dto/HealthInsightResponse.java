package com.isym444.networth.dto;

import java.time.Instant;

public record HealthInsightResponse(String commentary, Instant generatedAt) {
}
