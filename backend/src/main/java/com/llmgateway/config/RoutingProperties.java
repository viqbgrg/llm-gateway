package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.routing")
public record RoutingProperties(String strategy, Duration hedgeDelay, int maxHedgeCount) {}
