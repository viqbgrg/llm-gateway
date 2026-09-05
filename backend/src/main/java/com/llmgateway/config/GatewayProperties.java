package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String apiKey, String redisKeyPrefix) {
    public GatewayProperties {
        apiKey = apiKey == null ? "" : apiKey;
        redisKeyPrefix = redisKeyPrefix == null || redisKeyPrefix.isBlank() ? "llm-gateway" : redisKeyPrefix;
    }
}
