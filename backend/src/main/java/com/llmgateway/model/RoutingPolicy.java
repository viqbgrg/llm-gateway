package com.llmgateway.model;

import java.time.Duration;

public record RoutingPolicy(String id, String name, RoutingStrategy strategy, boolean hedgingEnabled,
                            Duration hedgeDelay, int maxHedgeCount, ExecutionPolicy execution,
                            AdaptivePolicy adaptive, long version) {
    public RoutingPolicy {
        if (strategy == null || hedgeDelay == null || hedgeDelay.isNegative() || hedgeDelay.toMillis() > 60_000
                || maxHedgeCount < 0 || maxHedgeCount > 2 || execution == null || adaptive == null
                || hedgingEnabled && !execution.allowReplay()) throw new IllegalArgumentException("Invalid routing policy");
    }
    public static RoutingPolicy defaults() {
        return new RoutingPolicy(null, "Default", RoutingStrategy.PRIORITY, false, Duration.ofMillis(800), 1,
                ExecutionPolicy.defaults(), AdaptivePolicy.defaults(), 0);
    }
}
