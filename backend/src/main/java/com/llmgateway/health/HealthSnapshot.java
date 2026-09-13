package com.llmgateway.health;

import java.time.Instant;

public record HealthSnapshot(HealthStatus status, long successCount, long failureCount, long timeoutCount,
                             long consecutiveFailures, double averageLatencyMs, Instant lastSuccessAt,
                             Instant lastFailureAt, long rateLimitedCount, long cancelledCount,
                             double averageTtftMs, long ttftSamples, double successEwma, double penalty,
                             Instant sampledAt, boolean expired, long nonStreamingSamples, double averageFullLatencyMs) {
    public static HealthSnapshot unknown() {
        return new HealthSnapshot(HealthStatus.UNKNOWN, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, 0.5, 0, null, true, 0, 0);
    }
    public long samples() { return successCount + failureCount; }
}
