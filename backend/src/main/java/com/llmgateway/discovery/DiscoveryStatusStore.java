package com.llmgateway.discovery;

import com.llmgateway.infrastructure.RedisKeyNamespace;
import com.llmgateway.protocol.ProtocolJson;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class DiscoveryStatusStore {
    private static final RedisScript<Long> WRITE = RedisScript.of("""
            local old = redis.call('GET', KEYS[1])
            if old and cjson.decode(old).generation > tonumber(ARGV[1]) then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1
            """, Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final RedisKeyNamespace keys;
    public DiscoveryStatusStore(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys) { this.redis = redis; this.keys = keys; }
    public Mono<Status> read(String provider) {
        return redis.opsForValue().get(keys.discoveryStatus(provider)).map(json -> {
            try { return ProtocolJson.MAPPER.readValue(json, Status.class); }
            catch (Exception ignored) { throw new IllegalStateException("Invalid discovery runtime state"); }
        });
    }
    public Mono<Status> initialize(String provider, long next) {
        Status initial = new Status(provider, "WAITING", null, null, next, 0, 0, 0, 0, 0, 0, 0, null, 0, 0);
        return redis.opsForValue().setIfAbsent(keys.discoveryStatus(provider), ProtocolJson.MAPPER.valueToTree(initial).toString(), Duration.ofDays(7))
                .then(read(provider));
    }
    public Mono<Void> write(Status status, long interval) {
        return redis.execute(WRITE, List.of(keys.discoveryStatus(status.providerId())), List.of(Long.toString(status.generation()),
                ProtocolJson.MAPPER.valueToTree(status).toString(), Long.toString(Math.max(Duration.ofDays(7).toMillis(), interval * 3)))).then();
    }
    public record Status(String providerId, String state, Long lastAttemptAt, Long lastSuccessAt, long nextRunAt, long durationMs,
                         int created, int updated, int total, int missing, int removed, int reappeared, String failureCode,
                         long generation, int consecutiveFailures) {}
}
