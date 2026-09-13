package com.llmgateway.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface BindingRepository extends ReactiveCrudRepository<BindingEntity, String> {
    Flux<BindingEntity> findByVirtualModelId(String virtualModelId);
    Flux<BindingEntity> findByProviderId(String providerId);
}
