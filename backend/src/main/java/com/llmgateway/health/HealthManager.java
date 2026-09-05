package com.llmgateway.health;

import reactor.core.publisher.Mono;

public interface HealthManager {
    Mono<HealthSnapshot> provider(String providerId);
    Mono<HealthSnapshot> binding(String bindingId);
    Mono<HealthSnapshot> model(String providerModelId);
    Mono<Void> recordSuccess(String bindingId, long latencyMs);
    Mono<Void> recordFailure(String bindingId, boolean timeout);
}
