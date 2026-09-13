package com.llmgateway.admin;

import com.llmgateway.discovery.ProviderModelDiscovery;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ProviderOperationsService {
    private final ProviderRepository providers;
    private final ProviderModelDiscovery discovery;
    private final com.llmgateway.infrastructure.credentials.CredentialService credentials;
    private final com.llmgateway.discovery.DiscoveryCoordinator coordinator;

    public ProviderOperationsService(ProviderRepository providers, ProviderModelDiscovery discovery,
                                     com.llmgateway.infrastructure.credentials.CredentialService credentials,
                                     com.llmgateway.discovery.DiscoveryCoordinator coordinator) {
        this.providers = providers;
        this.discovery = discovery;
        this.credentials = credentials;
        this.coordinator = coordinator;
    }

    public Mono<AdminDtos.ConnectionTestResponse> testConnection(String id) {
        return provider(id).flatMap(provider -> discovery.discover(credentials.access(provider)).elapsed()
                .map(result -> new AdminDtos.ConnectionTestResponse(true, result.getT2().size(), result.getT1())));
    }

    public Mono<AdminDtos.ModelSyncResponse> syncModels(String id) {
        return provider(id).then(coordinator.manual(id)).map(result -> new AdminDtos.ModelSyncResponse(result.created(), result.updated(), result.total()));
    }

    private Mono<ProviderEntity> provider(String id) {
        return AdminValidation.required(providers.findById(id), "Provider");
    }

}
