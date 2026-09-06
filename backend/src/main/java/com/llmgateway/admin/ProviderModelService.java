package com.llmgateway.admin;

import com.llmgateway.model.ProviderModelStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class ProviderModelService {
    private final ProviderModelRepository repository;
    private final ProviderRepository providers;
    private final AdminValidation validation;
    public ProviderModelService(ProviderModelRepository repository, ProviderRepository providers, AdminValidation validation) {
        this.repository = repository;
        this.providers = providers;
        this.validation = validation;
    }
    public Flux<ProviderModelEntity> list(String providerId) { return providerId == null ? repository.findAll() : repository.findByProviderId(providerId); }
    public Mono<ProviderModelEntity> get(String id) { return AdminValidation.required(repository.findById(id), "Provider model"); }
    public Mono<ProviderModelEntity> save(String id, AdminDtos.ProviderModelRequest r) {
        validation.json(r.capabilities(), "capabilities", true);
        validation.json(r.rawMetadata(), "rawMetadata", false);
        Mono<ProviderModelEntity> current = id == null
                ? Mono.just(new ProviderModelEntity(AdminMapping.id(null), r.providerId(), r.modelName(), r.displayName(),
                        ProviderModelStatus.NEW, "[]", null, Instant.now(), Instant.now(), Instant.now(), Instant.now(), null))
                : get(id);
        return AdminValidation.required(providers.findById(r.providerId()), "Provider").then(current)
            .map(e -> {
                if (!e.providerId().equals(r.providerId())) {
                    throw new IllegalArgumentException("The provider of an existing model cannot be changed");
                }
                return new ProviderModelEntity(e.id(), r.providerId(), r.modelName(), r.displayName(),
                r.status() == null ? e.status() : r.status(), r.capabilities() == null ? e.capabilities() : r.capabilities(),
                r.rawMetadata() == null ? e.rawMetadata() : r.rawMetadata(), e.firstSeenAt(), e.lastSeenAt(), e.createdAt(), Instant.now(), e.version());
            })
            .flatMap(repository::save);
    }
    public Mono<Void> delete(String id) { return get(id).flatMap(repository::delete); }
}
