package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class ProviderService {
    private final ProviderRepository repository;
    public ProviderService(ProviderRepository repository) { this.repository = repository; }
    public Flux<AdminDtos.ProviderResponse> list() { return repository.findAll().map(AdminMapping::providerResponse); }
    public Mono<AdminDtos.ProviderResponse> get(String id) { return AdminValidation.required(repository.findById(id), "Provider").map(AdminMapping::providerResponse); }
    public Mono<AdminDtos.ProviderResponse> save(String id, AdminDtos.ProviderRequest r) {
        AdminValidation.httpUrl(r.baseUrl(), "baseUrl");
        if (r.modelDiscoveryUrl() != null && !r.modelDiscoveryUrl().isBlank()) {
            AdminValidation.httpUrl(r.modelDiscoveryUrl(), "modelDiscoveryUrl");
        }
        Mono<ProviderEntity> current = id == null
                ? Mono.just(new ProviderEntity(AdminMapping.id(null), null, null, null, true,
                        Protocol.CHAT_COMPLETIONS, 5000, 30000, 60000, 0, false, null, 1800000,
                        Instant.now(), Instant.now(), null))
                : AdminValidation.required(repository.findById(id), "Provider");
        return current
            .map(existing -> new ProviderEntity(existing.id(), r.name(), r.baseUrl(), apiKey(r.apiKey(), existing.apiKey()),
                r.enabled() == null ? existing.enabled() : r.enabled(), r.protocol() == null ? existing.protocol() : r.protocol(),
                value(r.connectTimeoutMs(), existing.connectTimeoutMs()), value(r.readTimeoutMs(), existing.readTimeoutMs()),
                value(r.requestTimeoutMs(), existing.requestTimeoutMs()), value(r.maxRetries(), existing.maxRetries()),
                r.modelDiscoveryEnabled() == null ? existing.modelDiscoveryEnabled() : r.modelDiscoveryEnabled(),
                r.modelDiscoveryUrl() == null ? existing.modelDiscoveryUrl() : r.modelDiscoveryUrl(),
                value(r.modelDiscoveryIntervalMs(), existing.modelDiscoveryIntervalMs()), existing.createdAt(), Instant.now(), existing.version()))
            .flatMap(repository::save).map(AdminMapping::providerResponse);
    }
    public Mono<Void> delete(String id) { return AdminValidation.required(repository.findById(id), "Provider").flatMap(repository::delete); }
    private static String apiKey(String value, String existing) {
        if (value == null || "***".equals(value)) return existing;
        return value.isBlank() ? null : value;
    }
    private static long value(Long value, long fallback) { return value == null ? fallback : value; }
    private static int value(Integer value, int fallback) { return value == null ? fallback : value; }
}
