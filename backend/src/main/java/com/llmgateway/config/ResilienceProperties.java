package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.resilience")
public record ResilienceProperties(@org.springframework.boot.context.properties.bind.DefaultValue("8") int failureThreshold,
                                   @org.springframework.boot.context.properties.bind.DefaultValue("30s") Duration cooldown,
                                   @org.springframework.boot.context.properties.bind.DefaultValue("1") int halfOpenPermits) {
    public ResilienceProperties {
        if (failureThreshold <= 0 || cooldown == null || cooldown.isNegative() || cooldown.isZero()
                || halfOpenPermits < 1 || halfOpenPermits > 10) throw new IllegalArgumentException("Invalid resilience settings");
    }
}
