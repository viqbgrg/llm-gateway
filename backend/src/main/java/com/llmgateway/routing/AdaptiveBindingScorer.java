package com.llmgateway.routing;

import com.llmgateway.health.*;
import com.llmgateway.model.AdaptivePolicy;
import java.time.Instant;

public final class AdaptiveBindingScorer {
    private AdaptiveBindingScorer() {}
    public static Score score(HealthSnapshot health, boolean streaming, AdaptivePolicy policy, Instant now) {
        boolean sampled = !health.expired() && health.samples() >= policy.minimumSamples();
        double success = sampled ? health.successEwma() : 0.5;
        boolean latencyKnown = sampled && (streaming ? health.ttftSamples() : health.nonStreamingSamples()) >= policy.minimumSamples();
        double observed = streaming ? health.averageTtftMs() : health.averageLatencyMs();
        double latency = latencyKnown ? 1 / (1 + observed / policy.targetLatencyMs()) : 0.5;
        double healthScore = !sampled ? 0.5 : switch (health.status()) { case HEALTHY -> 1; case DEGRADED -> 0.5; case UNHEALTHY -> 0; case UNKNOWN -> 0.5; };
        double elapsed = health.sampledAt() == null ? 0 : Math.max(0, now.toEpochMilli() - health.sampledAt().toEpochMilli());
        double penalty = health.expired() ? 0 : health.penalty() * Math.pow(0.5, elapsed / policy.penaltyHalfLifeMs());
        return new Score(policy.successWeight() * success + policy.latencyWeight() * latency + policy.healthWeight() * healthScore
                - policy.failureWeight() * penalty, success, latency, healthScore, penalty, sampled);
    }
    public record Score(double total, double success, double latency, double health, double penalty, boolean sampled) {}
}
