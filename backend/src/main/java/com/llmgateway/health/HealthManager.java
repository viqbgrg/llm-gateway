package com.llmgateway.health;

import reactor.core.publisher.Mono;

public interface HealthManager {
    Mono<HealthSnapshot> provider(String providerId);
    Mono<HealthSnapshot> binding(String bindingId);
    Mono<HealthSnapshot> model(String providerModelId);
    Mono<Void> record(String bindingId, String providerId, String modelId, AttemptOutcome outcome, long latencyMs,
                      Long ttftMs, boolean streaming, long penaltyHalfLifeMs);
}
