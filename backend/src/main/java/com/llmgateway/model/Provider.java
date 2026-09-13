package com.llmgateway.model;

import java.time.Duration;
import java.time.Instant;

public record Provider(
        String id, String name, String baseUrl, String apiKey, boolean enabled, Protocol protocol,
        Duration connectTimeout, Duration readTimeout, Duration requestTimeout, int maxRetries,
        boolean modelDiscoveryEnabled, String modelDiscoveryUrl, Duration modelDiscoveryInterval,
        Instant createdAt, Instant updatedAt) {
    @Override public String toString() { return "Provider[id=" + id + ", credentials=redacted]"; }
}
