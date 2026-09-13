package com.llmgateway.admin;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class VirtualModelService {
    private final VirtualModelRepository repository;
    private final RoutingPolicyRepository policies;
    private final com.llmgateway.infrastructure.RuntimeStateCleanup runtime;
    public VirtualModelService(VirtualModelRepository repository, RoutingPolicyRepository policies, com.llmgateway.infrastructure.RuntimeStateCleanup runtime) { this.repository = repository; this.policies = policies; this.runtime = runtime; }
    public Flux<VirtualModelEntity> list() { return repository.findAll(); }
    public Mono<VirtualModelEntity> get(String id) { return AdminValidation.required(repository.findById(id), "Virtual model"); }
    public Mono<VirtualModelEntity> save(String id, AdminDtos.VirtualModelRequest r) {
        Mono<VirtualModelEntity> current = id == null
                ? Mono.just(new VirtualModelEntity(AdminMapping.id(null), null, null, null, true, null, Instant.now(), Instant.now(), null))
                : get(id);
        return (r.routingPolicyId() == null ? Mono.empty() : AdminValidation.required(policies.findById(r.routingPolicyId()), "Routing policy")).then(current)
            .map(e -> new VirtualModelEntity(e.id(), r.name(), r.displayName(), r.description(),
                r.enabled() == null ? e.enabled() : r.enabled(), r.routingPolicyId(), e.createdAt(), Instant.now(), e.version()))
            .flatMap(repository::save).flatMap(saved -> runtime.virtualModel(saved.id()).thenReturn(saved));
    }
    public Mono<Void> delete(String id) { return get(id).flatMap(repository::delete).then(runtime.virtualModel(id)); }
}
