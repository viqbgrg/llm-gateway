package com.llmgateway.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface VirtualModelRepository extends ReactiveCrudRepository<VirtualModelEntity, String> {
    reactor.core.publisher.Mono<VirtualModelEntity> findByName(String name);
    reactor.core.publisher.Flux<VirtualModelEntity> findByRoutingPolicyId(String routingPolicyId);
}
