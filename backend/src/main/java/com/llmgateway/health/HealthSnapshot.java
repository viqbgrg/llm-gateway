package com.llmgateway.health;

import java.time.Instant;

public record HealthSnapshot(HealthStatus status, long successCount, long failureCount, long timeoutCount,
                             long consecutiveFailures, double averageLatencyMs, Instant lastSuccessAt,
                             Instant lastFailureAt) {}
