package com.llmgateway.infrastructure;

import com.llmgateway.admin.BindingEntity;
import com.llmgateway.admin.BindingRepository;
import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Invoked after database commits; version checks and TTLs remain authoritative if Redis is unavailable. */
@Component
public class RuntimeStateCleanup {
    private final ReactiveStringRedisTemplate redis;
    private final RedisKeyNamespace keys;
    private final BindingRepository bindings;
    private final GatewayMetrics metrics;
    public RuntimeStateCleanup(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys, BindingRepository bindings, GatewayMetrics metrics) { this.redis = redis; this.keys = keys; this.bindings = bindings; this.metrics = metrics; }
    public Mono<Void> virtualModel(String id) { return safe(redis.delete(keys.preferredVirtualModel(id), keys.exploration(id)).then()); }
    public Mono<Void> bindingChanged(BindingEntity b) { return safe(redis.delete(keys.preferredVirtualModel(b.virtualModelId()), keys.bindingScore(b.id())).then()); }
    public Mono<Void> providerChanged(String id) { return safe(bindings.findByProviderId(id).concatMap(this::bindingChanged).then()); }
    public Mono<Void> modelChanged(String providerId) { return providerChanged(providerId); }
    public Mono<Void> bindingDeleted(BindingEntity b) {
        metrics.removeCircuit(b.id());
        return safe(redis.delete(keys.bindingHealth(b.id()), keys.bindingCircuit(b.id()), keys.bindingScore(b.id()), keys.preferredVirtualModel(b.virtualModelId())).then());
    }
    public Mono<Void> providerDeleted(String id) { return safe(redis.delete(keys.providerHealth(id), keys.providerModels(id), keys.discoveryLease(id), keys.discoveryStatus(id)).then()); }
    public Mono<Void> modelDeleted(String id) { return safe(redis.delete(keys.modelHealth(id)).then()); }
    private Mono<Void> safe(Mono<Void> operation) { return operation.timeout(Duration.ofMillis(500)).onErrorResume(ignored -> Mono.empty()); }
}
