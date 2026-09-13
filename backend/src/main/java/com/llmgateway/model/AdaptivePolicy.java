package com.llmgateway.model;

public record AdaptivePolicy(double successWeight, double latencyWeight, double healthWeight, double failureWeight,
                             long targetLatencyMs, int minimumSamples, long penaltyHalfLifeMs, double switchThreshold,
                             long minimumHoldMs, long preferenceTtlMs) {
    public static AdaptivePolicy defaults() { return new AdaptivePolicy(0.5, 0.3, 0.2, 0.5, 1000, 5, 60_000, 0.1, 30_000, 300_000); }
    public AdaptivePolicy {
        for (double w : new double[]{successWeight, latencyWeight, healthWeight, failureWeight, switchThreshold}) {
            if (!Double.isFinite(w) || w < 0 || w > 10) throw new IllegalArgumentException("Invalid adaptive weight");
        }
        if (successWeight + latencyWeight + healthWeight == 0 || targetLatencyMs < 1 || targetLatencyMs > 600_000
                || minimumSamples < 1 || minimumSamples > 10_000 || penaltyHalfLifeMs < 1 || penaltyHalfLifeMs > 86_400_000
                || minimumHoldMs < 0 || preferenceTtlMs < Math.max(1, minimumHoldMs) || preferenceTtlMs > 86_400_000) {
            throw new IllegalArgumentException("Invalid adaptive policy limits");
        }
    }
}
