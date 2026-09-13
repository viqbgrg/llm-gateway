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
        return new AdminDtos.ProviderResponse(e.id(), e.name(), e.baseUrl(), e.apiKey() == null && e.apiKeyCiphertext() == null ? null : "***",
                e.enabled(), e.protocol(), e.connectTimeoutMs(), e.readTimeoutMs(), e.requestTimeoutMs(), e.maxRetries(),
                e.modelDiscoveryEnabled(), e.modelDiscoveryUrl(), e.modelDiscoveryIntervalMs());
    }
}
