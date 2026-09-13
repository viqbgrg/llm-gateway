package com.llmgateway.admin;

import com.llmgateway.model.*;
import java.time.Duration;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("routing_policies")
public record RoutingPolicyEntity(@Id String id, String name, RoutingStrategy strategy, boolean hedgingEnabled,
        long hedgeDelayMs, int maxHedgeCount, long deadlineMs, int maxTotalAttempts, boolean allowReplay,
        long backoffMs, long maxBackoffMs, double jitter, int failureThreshold, long cooldownMs, int halfOpenPermits,
        double successWeight, double latencyWeight, double healthWeight, double failureWeight,
        long targetLatencyMs, int minimumSamples, long penaltyHalfLifeMs, double switchThreshold,
        long minimumHoldMs, long preferenceTtlMs, @Version Long version) {
    public RoutingPolicy domain() {
        return new RoutingPolicy(id, name, strategy, hedgingEnabled, Duration.ofMillis(hedgeDelayMs), maxHedgeCount,
                new ExecutionPolicy(deadlineMs, maxTotalAttempts, allowReplay, backoffMs, maxBackoffMs, jitter,
                        failureThreshold, cooldownMs, halfOpenPermits),
                new AdaptivePolicy(successWeight, latencyWeight, healthWeight, failureWeight, targetLatencyMs,
                        minimumSamples, penaltyHalfLifeMs, switchThreshold, minimumHoldMs, preferenceTtlMs), version == null ? 0 : version);
    }
    public static RoutingPolicyEntity from(String id, PolicyRequest request) {
        ExecutionPolicy e = request.execution() == null ? ExecutionPolicy.defaults() : request.execution();
        AdaptivePolicy a = request.adaptive() == null ? AdaptivePolicy.defaults() : request.adaptive();
        return new RoutingPolicyEntity(id, request.name(), request.strategy(), request.hedgingEnabled(), request.hedgeDelayMs(),
                request.maxHedgeCount(), e.deadlineMs(), e.maxTotalAttempts(), e.allowReplay(), e.backoffMs(), e.maxBackoffMs(),
                e.jitter(), e.failureThreshold(), e.cooldownMs(), e.halfOpenPermits(), a.successWeight(), a.latencyWeight(),
                a.healthWeight(), a.failureWeight(), a.targetLatencyMs(), a.minimumSamples(), a.penaltyHalfLifeMs(),
                a.switchThreshold(), a.minimumHoldMs(), a.preferenceTtlMs(), request.version());
    }
    public record PolicyRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128) String name,
                                @jakarta.validation.constraints.NotNull RoutingStrategy strategy, boolean hedgingEnabled,
                                long hedgeDelayMs, int maxHedgeCount, ExecutionPolicy execution, AdaptivePolicy adaptive, Long version) {}
}
