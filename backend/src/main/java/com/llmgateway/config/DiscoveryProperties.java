package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.discovery")
public record DiscoveryProperties(boolean enabled, Duration defaultInterval) {
    public DiscoveryProperties {
        if (defaultInterval == null) defaultInterval = Duration.ofMinutes(30);
    }
}
