package com.llmgateway.admin;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class VirtualModelService {
    private final VirtualModelRepository repository;
    public VirtualModelService(VirtualModelRepository repository) { this.repository = repository; }
    public Flux<VirtualModelEntity> list() { return repository.findAll(); }
    public Mono<VirtualModelEntity> get(String id) { return repository.findById(id); }
    public Mono<VirtualModelEntity> save(String id, AdminDtos.VirtualModelRequest r) {
        return repository.findById(id == null ? "" : id).defaultIfEmpty(new VirtualModelEntity(AdminMapping.id(id), null, null, null, true, null, Instant.now(), Instant.now(), null))
            .map(e -> new VirtualModelEntity(e.id(), r.name(), r.displayName(), r.description(),
                r.enabled() == null ? e.enabled() : r.enabled(), r.routingPolicyId(), e.createdAt(), Instant.now(), e.version()))
            .flatMap(repository::save);
    }
    public Mono<Void> delete(String id) { return repository.deleteById(id); }
}
