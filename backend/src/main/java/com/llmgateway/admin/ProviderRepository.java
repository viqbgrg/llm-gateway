package com.llmgateway.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Mono;

public interface ProviderRepository extends ReactiveCrudRepository<ProviderEntity, String> {
    @Query("SELECT * FROM providers WHERE id = :id FOR UPDATE")
    Mono<ProviderEntity> findByIdForUpdate(String id);
}
