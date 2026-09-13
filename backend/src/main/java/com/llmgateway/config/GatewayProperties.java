package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String apiKey, String redisKeyPrefix, String adminApiKey) {
    public GatewayProperties(String apiKey, String redisKeyPrefix) {
        this(apiKey, redisKeyPrefix, null);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GatewayProperties {
        apiKey = apiKey == null ? "" : apiKey;
        redisKeyPrefix = redisKeyPrefix == null || redisKeyPrefix.isBlank() ? "llm-gateway" : redisKeyPrefix;
        adminApiKey = adminApiKey == null || adminApiKey.isBlank() ? apiKey : adminApiKey;
    }
    @Override public String toString() { return "GatewayProperties[credentials redacted]"; }
}
