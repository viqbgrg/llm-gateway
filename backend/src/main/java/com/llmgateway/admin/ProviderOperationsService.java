package com.llmgateway.admin;

import com.llmgateway.discovery.DiscoveredModel;
import com.llmgateway.discovery.ProviderModelDiscovery;
import com.llmgateway.model.ProviderModelStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Service
public class ProviderOperationsService {
    private final ProviderRepository providers;
    private final ProviderModelRepository models;
    private final ProviderModelDiscovery discovery;
    private final TransactionalOperator transactions;

    public ProviderOperationsService(ProviderRepository providers, ProviderModelRepository models,
                                     ProviderModelDiscovery discovery, ReactiveTransactionManager transactionManager) {
        this.providers = providers;
        this.models = models;
        this.discovery = discovery;
        this.transactions = TransactionalOperator.create(transactionManager);
    }

    public Mono<AdminDtos.ConnectionTestResponse> testConnection(String id) {
        return provider(id).flatMap(provider -> discovery.discover(AdminMapping.provider(provider)).elapsed()
                .map(result -> new AdminDtos.ConnectionTestResponse(true, result.getT2().size(), result.getT1())));
    }

    public Mono<AdminDtos.ModelSyncResponse> syncModels(String id) {
        return provider(id).flatMap(provider -> discovery.discover(AdminMapping.provider(provider)))
                .flatMap(catalog -> importModels(id, catalog).as(transactions::transactional));
    }

    private Mono<ProviderEntity> provider(String id) {
        return AdminValidation.required(providers.findById(id), "Provider");
    }

    private Mono<AdminDtos.ModelSyncResponse> importModels(String providerId, List<DiscoveredModel> catalog) {
        Instant now = Instant.now();
        return AdminValidation.required(providers.findByIdForUpdate(providerId), "Provider")
                .thenMany(Flux.fromIterable(catalog)).concatMap(discovered ->
                models.findByProviderIdAndModelName(providerId, discovered.modelName())
                        .flatMap(existing -> models.save(new ProviderModelEntity(existing.id(), providerId,
                                existing.modelName(), existing.displayName(), existing.status(), existing.capabilities(),
                                discovered.rawMetadata().toString(), existing.firstSeenAt(), now,
                                existing.createdAt(), now, existing.version())).thenReturn(false))
                        .switchIfEmpty(Mono.defer(() -> models.save(new ProviderModelEntity(AdminMapping.id(null),
                                providerId, discovered.modelName(), discovered.displayName(), ProviderModelStatus.NEW,
                                "[]", discovered.rawMetadata().toString(), now, now, now, now, null)).thenReturn(true))))
                .collectList()
                .map(created -> {
                    int count = (int) created.stream().filter(Boolean::booleanValue).count();
                    return new AdminDtos.ModelSyncResponse(count, created.size() - count, created.size());
                });
    }
}
