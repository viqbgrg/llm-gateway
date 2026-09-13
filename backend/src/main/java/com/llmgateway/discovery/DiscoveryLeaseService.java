package com.llmgateway.discovery;

import com.llmgateway.infrastructure.RedisKeyNamespace;
import com.llmgateway.inference.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DiscoveryLeaseService {
    private static final RedisScript<Long> RENEW = RedisScript.of("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0", Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final RedisKeyNamespace keys;
    public DiscoveryLeaseService(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys) { this.redis = redis; this.keys = keys; }
    public Mono<Lease> acquire(String provider, Duration ttl) {
        return Mono.defer(() -> {
            Lease lease = new Lease(provider, UUID.randomUUID().toString(), ttl);
            return redis.opsForValue().setIfAbsent(keys.discoveryLease(provider), lease.owner(), ttl).filter(Boolean::booleanValue).map(ignored -> lease);
        }).onErrorMap(e -> new GatewayException(GatewayError.STORAGE_UNAVAILABLE));
    }
    public Mono<Boolean> owned(Lease lease) { return redis.opsForValue().get(keys.discoveryLease(lease.providerId())).map(lease.owner()::equals).defaultIfEmpty(false); }
    public Mono<Void> heartbeat(Lease lease) {
        return Flux.interval(lease.ttl().dividedBy(3)).concatMap(tick -> redis.execute(RENEW, List.of(keys.discoveryLease(lease.providerId())),
                List.of(lease.owner(), Long.toString(lease.ttl().toMillis()))).next()).concatMap(renewed -> renewed == 1 ? Mono.empty()
                : Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED))).then();
    }
    public Mono<Void> release(Lease lease) { return redis.execute(RELEASE, List.of(keys.discoveryLease(lease.providerId())), List.of(lease.owner())).then().onErrorResume(e -> Mono.empty()); }
    public Mono<Boolean> busy(String providerId) { return redis.hasKey(keys.discoveryLease(providerId)); }
    public record Lease(String providerId, String owner, Duration ttl) {}
}
