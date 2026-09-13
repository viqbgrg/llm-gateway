package com.llmgateway.resilience;

import reactor.core.publisher.Mono;
import com.llmgateway.health.AttemptOutcome;
import com.llmgateway.model.ExecutionPolicy;
import java.time.Duration;

public interface CircuitBreaker {
    Mono<CircuitSnapshot> snapshot(String bindingId);
    Mono<Permit> acquire(String bindingId, String configurationVersion, String attemptId, Duration lifetime, ExecutionPolicy policy);
    Mono<Void> settle(Permit permit, AttemptOutcome outcome, ExecutionPolicy policy, Duration retryAfter);
    record Permit(String bindingId, String attemptId, String configurationVersion, long generation) {}
}
