package com.llmgateway.resilience;

import java.time.Instant;

public record CircuitSnapshot(CircuitState state, Instant openUntil, Instant rateLimitedUntil, int activePermits,
                              String configurationVersion, boolean configurationBlocked, Instant sampledAt, boolean expired) {
    public boolean available(Instant now, String version) {
        if (configurationVersion != null && !configurationVersion.equals(version)) return true;
        return !configurationBlocked && (rateLimitedUntil == null || !rateLimitedUntil.isAfter(now))
                && (state != CircuitState.OPEN || openUntil == null || !openUntil.isAfter(now));
    }
    public static CircuitSnapshot unknown() { return new CircuitSnapshot(CircuitState.CLOSED, null, null, 0, null, false, null, true); }
}
