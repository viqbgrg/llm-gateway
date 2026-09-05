package com.llmgateway.admin;

import com.llmgateway.model.ProviderModelStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class ProviderModelService {
    private final ProviderModelRepository repository;
    public ProviderModelService(ProviderModelRepository repository) { this.repository = repository; }
    public Flux<ProviderModelEntity> list(String providerId) { return providerId == null ? repository.findAll() : repository.findByProviderId(providerId); }
    public Mono<ProviderModelEntity> get(String id) { return repository.findById(id); }
    public Mono<ProviderModelEntity> save(String id, AdminDtos.ProviderModelRequest r) {
        return repository.findById(id == null ? "" : id).defaultIfEmpty(new ProviderModelEntity(AdminMapping.id(id), r.providerId(),
                r.modelName(), r.displayName(), ProviderModelStatus.NEW, "[]", null, Instant.now(), Instant.now(), Instant.now(), Instant.now(), null))
            .map(e -> new ProviderModelEntity(e.id(), r.providerId(), r.modelName(), r.displayName(),
                r.status() == null ? e.status() : r.status(), r.capabilities() == null ? e.capabilities() : r.capabilities(),
                r.rawMetadata() == null ? e.rawMetadata() : r.rawMetadata(), e.firstSeenAt(), Instant.now(), e.createdAt(), Instant.now(), e.version()))
            .flatMap(repository::save);
    }
    public Mono<Void> delete(String id) { return repository.deleteById(id); }
}
