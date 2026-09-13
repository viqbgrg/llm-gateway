package com.llmgateway.model;

public record ExecutionPolicy(long deadlineMs, int maxTotalAttempts, boolean allowReplay, long backoffMs,
                              long maxBackoffMs, double jitter, int failureThreshold, long cooldownMs, int halfOpenPermits) {
    public static ExecutionPolicy defaults() { return new ExecutionPolicy(60_000, 3, false, 100, 2_000, 0.2, 8, 30_000, 1); }
    public ExecutionPolicy {
        if (deadlineMs < 1 || deadlineMs > 600_000 || maxTotalAttempts < 1 || maxTotalAttempts > 20
                || backoffMs < 0 || maxBackoffMs < backoffMs || maxBackoffMs > 60_000
                || !Double.isFinite(jitter) || jitter < 0 || jitter > 1 || failureThreshold < 1 || failureThreshold > 1000
                || cooldownMs < 1 || cooldownMs > 3_600_000 || halfOpenPermits < 1 || halfOpenPermits > 10) {
            throw new IllegalArgumentException("Invalid execution policy limits");
        }
    }
}
