package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class BindingService {
    private final BindingRepository repository;
    public BindingService(BindingRepository repository) { this.repository = repository; }
    public Flux<BindingEntity> list(String virtualModelId) { return virtualModelId == null ? repository.findAll() : repository.findByVirtualModelId(virtualModelId); }
    public Mono<BindingEntity> get(String id) { return repository.findById(id); }
    public Mono<BindingEntity> save(String id, AdminDtos.BindingRequest r) {
        return repository.findById(id == null ? "" : id).defaultIfEmpty(new BindingEntity(AdminMapping.id(id), r.virtualModelId(), r.providerId(), r.providerModelId(),
                true, 0, false, Protocol.CHAT_COMPLETIONS, Protocol.CHAT_COMPLETIONS, null, Instant.now(), Instant.now(), null))
            .map(e -> new BindingEntity(e.id(), r.virtualModelId(), r.providerId(), r.providerModelId(),
                r.enabled() == null ? e.enabled() : r.enabled(), r.priority() == null ? e.priority() : r.priority(),
                r.translationEnabled() == null ? e.translationEnabled() : r.translationEnabled(),
                r.sourceProtocol() == null ? e.sourceProtocol() : r.sourceProtocol(),
                r.targetProtocol() == null ? e.targetProtocol() : r.targetProtocol(),
                r.capabilitiesOverride() == null ? e.capabilitiesOverride() : r.capabilitiesOverride(), e.createdAt(), Instant.now(), e.version()))
            .flatMap(repository::save);
    }
    public Mono<Void> delete(String id) { return repository.deleteById(id); }
}
