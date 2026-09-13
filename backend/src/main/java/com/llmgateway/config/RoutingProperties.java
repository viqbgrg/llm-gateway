package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import com.llmgateway.model.*;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gateway.routing")
public record RoutingProperties(@DefaultValue("PRIORITY") RoutingStrategy strategy, @DefaultValue("800ms") Duration hedgeDelay,
        @DefaultValue("1") int maxHedgeCount, @DefaultValue("false") boolean hedgingEnabled,
        @DefaultValue("3") int maxTotalAttempts, @DefaultValue("false") boolean allowReplay,
        @DefaultValue("100ms") Duration backoff, @DefaultValue("2s") Duration maxBackoff, @DefaultValue("0.2") double jitter) {
    public RoutingProperties {
        if (strategy == null || hedgeDelay == null || hedgeDelay.isNegative() || hedgeDelay.toMillis() > 60_000
                || maxHedgeCount < 0 || maxHedgeCount > 2 || maxTotalAttempts < 1 || maxTotalAttempts > 20
                || hedgingEnabled && !allowReplay || backoff == null || maxBackoff == null) throw new IllegalArgumentException("Invalid routing defaults");
        new ExecutionPolicy(60_000, maxTotalAttempts, allowReplay, backoff.toMillis(), maxBackoff.toMillis(), jitter, 8, 30_000, 1);
    }
    public RoutingPolicy policy(InferenceProperties inference, ResilienceProperties resilience) {
        return new RoutingPolicy(null, "Default", strategy, hedgingEnabled, hedgeDelay, maxHedgeCount,
                new ExecutionPolicy(inference.deadline().toMillis(), maxTotalAttempts, allowReplay, backoff.toMillis(), maxBackoff.toMillis(), jitter,
                        resilience.failureThreshold(), resilience.cooldown().toMillis(), resilience.halfOpenPermits()), AdaptivePolicy.defaults(), 0);
    }
}
