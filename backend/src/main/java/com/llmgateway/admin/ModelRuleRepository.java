package com.llmgateway.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ModelRuleRepository extends ReactiveCrudRepository<ModelRuleEntity, String> {
    Flux<ModelRuleEntity> findByEnabledTrue();
    Flux<ModelRuleEntity> findByPatternAndPriorityAndEnabledTrue(String pattern, int priority);
}
