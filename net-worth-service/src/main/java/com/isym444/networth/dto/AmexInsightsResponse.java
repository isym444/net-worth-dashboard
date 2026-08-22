package com.isym444.networth.dto;

import java.time.Instant;

public record AmexInsightsResponse(String commentary, Instant generatedAt, int transactionCount) {
}
