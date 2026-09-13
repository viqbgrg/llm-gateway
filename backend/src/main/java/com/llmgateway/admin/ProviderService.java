package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class ProviderService {
    private final ProviderRepository repository;
    private final BindingRepository bindings;
    private final com.llmgateway.infrastructure.credentials.CredentialService credentials;
    private final com.llmgateway.protocol.TranslationValidator translation;
    private final com.llmgateway.infrastructure.RuntimeStateCleanup runtime;
    private final org.springframework.transaction.reactive.TransactionalOperator transaction;
    public ProviderService(ProviderRepository repository, BindingRepository bindings,
                           com.llmgateway.infrastructure.credentials.CredentialService credentials,
                           com.llmgateway.protocol.TranslationValidator translation, com.llmgateway.infrastructure.RuntimeStateCleanup runtime,
                           org.springframework.transaction.ReactiveTransactionManager transactionManager) {
        this.repository = repository; this.bindings = bindings; this.credentials = credentials; this.translation = translation;
        this.runtime = runtime;
        this.transaction = org.springframework.transaction.reactive.TransactionalOperator.create(transactionManager);
    }
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
                : AdminValidation.required(repository.findByIdForUpdate(id), "Provider");
        return current
            .flatMap(existing -> {
                var key = credentials.store(existing.id(), r.apiKey(), existing.apiKey(), existing.apiKeyCiphertext());
                Protocol protocol = r.protocol() == null ? existing.protocol() : r.protocol();
                return bindings.findByProviderId(existing.id()).doOnNext(binding -> {
                    if (protocol != existing.protocol()) translation.validate(binding.sourceProtocol(), binding.targetProtocol(), protocol, binding.translationEnabled());
                }).then(Mono.just(new ProviderEntity(existing.id(), r.name(), r.baseUrl(), key.plaintext(),
                r.enabled() == null ? existing.enabled() : r.enabled(), r.protocol() == null ? existing.protocol() : r.protocol(),
                value(r.connectTimeoutMs(), existing.connectTimeoutMs()), value(r.readTimeoutMs(), existing.readTimeoutMs()),
                value(r.requestTimeoutMs(), existing.requestTimeoutMs()), value(r.maxRetries(), existing.maxRetries()),
                r.modelDiscoveryEnabled() == null ? existing.modelDiscoveryEnabled() : r.modelDiscoveryEnabled(),
                r.modelDiscoveryUrl() == null ? existing.modelDiscoveryUrl() : r.modelDiscoveryUrl(),
                value(r.modelDiscoveryIntervalMs(), existing.modelDiscoveryIntervalMs()), existing.createdAt(), Instant.now(), existing.version(), key.ciphertext())));
            })
            .flatMap(repository::save).as(transaction::transactional).flatMap(saved -> runtime.providerChanged(saved.id()).thenReturn(saved)).map(AdminMapping::providerResponse);
    }
    public Mono<Void> delete(String id) { return AdminValidation.required(repository.findById(id), "Provider").flatMap(repository::delete).then(runtime.providerDeleted(id)); }
    private static long value(Long value, long fallback) { return value == null ? fallback : value; }
    private static int value(Integer value, int fallback) { return value == null ? fallback : value; }
}
