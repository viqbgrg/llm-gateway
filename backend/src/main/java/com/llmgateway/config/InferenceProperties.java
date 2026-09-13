package com.llmgateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gateway.inference")
public record InferenceProperties(@DefaultValue("60s") Duration deadline,
                                  @DefaultValue("8388608") int maxRequestBytes,
                                  @DefaultValue("8388608") int maxResponseBytes,
                                  @DefaultValue("1048576") int maxSseEventBytes,
                                  @DefaultValue("30s") Duration slowConsumerTimeout) {
    public InferenceProperties {
        if (deadline == null || deadline.isNegative() || deadline.isZero() || deadline.toMillis() > 600_000
                || maxRequestBytes < 1024 || maxRequestBytes > 32 * 1024 * 1024
                || maxResponseBytes < 1024 || maxResponseBytes > 32 * 1024 * 1024
                || maxSseEventBytes < 1024 || maxSseEventBytes > maxResponseBytes
                || slowConsumerTimeout == null || slowConsumerTimeout.isNegative() || slowConsumerTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid inference limits");
        }
    }
}
