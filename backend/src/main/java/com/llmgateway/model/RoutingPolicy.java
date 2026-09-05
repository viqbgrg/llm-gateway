package com.llmgateway.model;

import java.time.Duration;

public record RoutingPolicy(String id, String name, RoutingStrategy strategy, boolean hedgingEnabled,
                            Duration hedgeDelay, int maxHedgeCount) {}
