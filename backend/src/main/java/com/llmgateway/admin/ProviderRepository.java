package com.llmgateway.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ProviderRepository extends ReactiveCrudRepository<ProviderEntity, String> {}
