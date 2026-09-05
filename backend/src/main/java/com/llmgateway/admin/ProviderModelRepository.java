package com.llmgateway.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ProviderModelRepository extends ReactiveCrudRepository<ProviderModelEntity, String> {
    Flux<ProviderModelEntity> findByProviderId(String providerId);
}
