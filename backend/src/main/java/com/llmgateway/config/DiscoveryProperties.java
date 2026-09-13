package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.discovery")
public record DiscoveryProperties(@org.springframework.boot.context.properties.bind.DefaultValue("true") boolean enabled,
        @org.springframework.boot.context.properties.bind.DefaultValue("30m") Duration defaultInterval,
        @org.springframework.boot.context.properties.bind.DefaultValue("30s") Duration scanInterval,
        @org.springframework.boot.context.properties.bind.DefaultValue("15s") Duration leaseTtl,
        @org.springframework.boot.context.properties.bind.DefaultValue("5s") Duration manualWait,
        @org.springframework.boot.context.properties.bind.DefaultValue("2") int missingConfirmations,
        @org.springframework.boot.context.properties.bind.DefaultValue("4") int concurrency,
        @org.springframework.boot.context.properties.bind.DefaultValue("5s") Duration startupJitter) {
    public DiscoveryProperties {
        if (defaultInterval == null || defaultInterval.isNegative() || defaultInterval.isZero()
                || scanInterval == null || scanInterval.toMillis() < 10 || leaseTtl == null || leaseTtl.toMillis() < 300
                || manualWait == null || manualWait.toMillis() < 1 || manualWait.toMillis() > 60_000
                || missingConfirmations < 1 || missingConfirmations > 100 || concurrency < 1 || concurrency > 32
                || startupJitter == null || startupJitter.isNegative()) throw new IllegalArgumentException("Invalid discovery settings");
    }
}
