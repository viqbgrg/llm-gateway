package com.llmgateway.resilience;

import reactor.core.publisher.Mono;

public interface CircuitBreaker {
    Mono<CircuitState> state(String bindingId);
    Mono<Boolean> allowRequest(String bindingId);
    Mono<Void> recordSuccess(String bindingId);
    Mono<Void> recordFailure(String bindingId);
}
