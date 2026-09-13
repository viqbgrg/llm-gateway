package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Service
public class BindingService {
    private final BindingRepository repository;
    private final ProviderModelRepository providerModels;
    private final VirtualModelRepository virtualModels;
    private final AdminValidation validation;
    private final ProviderRepository providers;
    private final com.llmgateway.protocol.TranslationValidator translation;
    private final com.llmgateway.infrastructure.RuntimeStateCleanup runtime;
    private final org.springframework.transaction.reactive.TransactionalOperator transaction;
    public BindingService(BindingRepository repository, ProviderModelRepository providerModels,
                          VirtualModelRepository virtualModels, AdminValidation validation, ProviderRepository providers,
                          com.llmgateway.protocol.TranslationValidator translation, com.llmgateway.infrastructure.RuntimeStateCleanup runtime,
                          org.springframework.transaction.ReactiveTransactionManager transactionManager) {
        this.repository = repository;
        this.providerModels = providerModels;
        this.virtualModels = virtualModels;
        this.validation = validation;
        this.providers = providers;
        this.translation = translation;
        this.runtime = runtime;
        this.transaction = org.springframework.transaction.reactive.TransactionalOperator.create(transactionManager);
    }
    public Flux<BindingEntity> list(String virtualModelId) { return virtualModelId == null ? repository.findAll() : repository.findByVirtualModelId(virtualModelId); }
    public Mono<BindingEntity> get(String id) { return AdminValidation.required(repository.findById(id), "Binding"); }
    public Mono<BindingEntity> save(String id, AdminDtos.BindingRequest r) {
        validation.json(r.capabilitiesOverride(), "capabilitiesOverride", true);
        Mono<BindingEntity> current = id == null
                ? Mono.just(new BindingEntity(AdminMapping.id(null), r.virtualModelId(), r.providerId(), r.providerModelId(),
                        true, 0, false, Protocol.CHAT_COMPLETIONS, Protocol.CHAT_COMPLETIONS, null, Instant.now(), Instant.now(), null))
                : get(id);
        return AdminValidation.required(providerModels.findById(r.providerModelId()), "Provider model")
            .flatMap(model -> model.providerId().equals(r.providerId())
                    ? AdminValidation.required(virtualModels.findById(r.virtualModelId()), "Virtual model")
                    : Mono.error(new IllegalArgumentException("Provider model does not belong to the selected provider")))
            .then(current)
            .map(e -> new BindingEntity(e.id(), r.virtualModelId(), r.providerId(), r.providerModelId(),
                r.enabled() == null ? e.enabled() : r.enabled(), r.priority() == null ? e.priority() : r.priority(),
                r.translationEnabled() == null ? e.translationEnabled() : r.translationEnabled(),
                r.sourceProtocol() == null ? e.sourceProtocol() : r.sourceProtocol(),
                r.targetProtocol() == null ? e.targetProtocol() : r.targetProtocol(),
                r.capabilitiesOverride(), e.createdAt(), Instant.now(), e.version()))
            .flatMap(binding -> AdminValidation.required(providers.findByIdForUpdate(binding.providerId()), "Provider").flatMap(provider -> {
                translation.validate(binding.sourceProtocol(), binding.targetProtocol(), provider.protocol(), binding.translationEnabled());
                return repository.save(binding);
            })).as(transaction::transactional).flatMap(saved -> runtime.bindingChanged(saved).thenReturn(saved));
    }
    public Mono<Void> delete(String id) { return get(id).flatMap(binding -> repository.delete(binding).then(runtime.bindingDeleted(binding))); }
}
