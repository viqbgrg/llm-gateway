package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.resilience")
public record ResilienceProperties(int failureThreshold, Duration cooldown) {
    public ResilienceProperties {
        if (failureThreshold <= 0) failureThreshold = 8;
        if (cooldown == null) cooldown = Duration.ofSeconds(30);
    }
}
