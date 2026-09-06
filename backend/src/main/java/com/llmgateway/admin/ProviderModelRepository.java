package com.llmgateway.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderModelRepository extends ReactiveCrudRepository<ProviderModelEntity, String> {
    Flux<ProviderModelEntity> findByProviderId(String providerId);
    Mono<ProviderModelEntity> findByProviderIdAndModelName(String providerId, String modelName);
}
