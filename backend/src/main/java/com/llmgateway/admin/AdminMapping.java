package com.llmgateway.admin;

import com.llmgateway.model.Provider;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

final class AdminMapping {
    private AdminMapping() {}
    static String id(String id) { return id == null || id.isBlank() ? UUID.randomUUID().toString() : id; }
    static Instant now() { return Instant.now(); }
    static AdminDtos.ProviderResponse providerResponse(ProviderEntity e) {
        return new AdminDtos.ProviderResponse(e.id(), e.name(), e.baseUrl(), e.apiKey() == null ? null : "***",
                e.enabled(), e.protocol(), e.connectTimeoutMs(), e.readTimeoutMs(), e.requestTimeoutMs(), e.maxRetries(),
                e.modelDiscoveryEnabled(), e.modelDiscoveryUrl(), e.modelDiscoveryIntervalMs());
    }
    static Provider provider(ProviderEntity e) {
        return new Provider(e.id(), e.name(), e.baseUrl(), e.apiKey(), e.enabled(), e.protocol(),
                Duration.ofMillis(e.connectTimeoutMs()), Duration.ofMillis(e.readTimeoutMs()),
                Duration.ofMillis(e.requestTimeoutMs()), e.maxRetries(), e.modelDiscoveryEnabled(),
                e.modelDiscoveryUrl(), Duration.ofMillis(e.modelDiscoveryIntervalMs()), e.createdAt(), e.updatedAt());
    }
}
