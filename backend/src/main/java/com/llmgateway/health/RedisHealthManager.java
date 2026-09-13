package com.llmgateway.health;

import com.llmgateway.infrastructure.RedisKeyNamespace;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisHealthManager implements HealthManager {
    private static final RedisScript<Long> RECORD = RedisScript.of("""
            local now = tonumber(ARGV[1])
            local start = tonumber(redis.call('HGET', KEYS[1], 'window') or '0')
            if now - start >= 900000 then redis.call('DEL', KEYS[1]); redis.call('HSET', KEYS[1], 'window', now) end
            local kind = ARGV[2]
            local latency = tonumber(ARGV[3])
            local ttft = tonumber(ARGV[4])
            local function inc(k) return redis.call('HINCRBY', KEYS[1], k, 1) end
            if kind == 'SUCCESS' or ARGV[5] == '1' then
                local count = inc('samples')
                local ewma = tonumber(redis.call('HGET', KEYS[1], 'ewma') or '0.5')
                local penalty = tonumber(redis.call('HGET', KEYS[1], 'penalty') or '0')
                local last = tonumber(redis.call('HGET', KEYS[1], 'sampled') or now)
                penalty = penalty * math.exp(-math.log(2) * math.max(0, now - last) / tonumber(ARGV[7]))
                local average = tonumber(redis.call('HGET', KEYS[1], 'fullLatency') or latency)
                if kind == 'SUCCESS' then
                    inc('success'); redis.call('HSET', KEYS[1], 'consecutive', 0, 'lastSuccess', now)
                    ewma = 0.2 + 0.8 * ewma
                else
                    inc('failure'); inc('consecutive'); redis.call('HSET', KEYS[1], 'lastFailure', now)
                    ewma = 0.8 * ewma; penalty = math.min(10, penalty + 1)
                    if kind == 'TIMEOUT' then inc('timeout') end
                end
                redis.call('HSET', KEYS[1], 'ewma', ewma, 'penalty', penalty, 'fullLatency', average + (latency - average) / count, 'sampled', now)
                if ARGV[6] == '0' then
                    local n = inc('nonStreamingSamples'); local avg = tonumber(redis.call('HGET', KEYS[1], 'latency') or latency)
                    redis.call('HSET', KEYS[1], 'latency', avg + (latency - avg) / n)
                end
                if ttft >= 0 then
                    local n = inc('ttftSamples'); local avg = tonumber(redis.call('HGET', KEYS[1], 'ttft') or ttft)
                    redis.call('HSET', KEYS[1], 'ttft', avg + (ttft - avg) / n)
                end
            elseif kind == 'RATE_LIMITED' then inc('rateLimited')
            elseif kind == 'CLIENT_CANCELLED' or kind == 'HEDGE_CANCELLED' then inc('cancelled') end
            redis.call('HSET', KEYS[1], 'updated', now)
            redis.call('EXPIRE', KEYS[1], 1800)
            return 1
            """, Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final RedisKeyNamespace keys;
    private final Clock clock;
    public RedisHealthManager(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys, Clock clock) { this.redis = redis; this.keys = keys; this.clock = clock; }
    @Override public Mono<HealthSnapshot> provider(String id) { return read(keys.providerHealth(id)); }
    @Override public Mono<HealthSnapshot> binding(String id) { return read(keys.bindingHealth(id)); }
    @Override public Mono<HealthSnapshot> model(String id) { return read(keys.modelHealth(id)); }
    @Override public Mono<Void> record(String binding, String provider, String model, AttemptOutcome outcome, long latencyMs,
                                       Long ttftMs, boolean streaming, long penaltyHalfLifeMs) {
        List<String> args = List.of(Long.toString(clock.millis()), outcome.name(), Long.toString(latencyMs),
                Long.toString(ttftMs == null ? -1 : ttftMs), outcome.failure() ? "1" : "0", streaming ? "1" : "0", Long.toString(penaltyHalfLifeMs));
        // Each object is updated atomically. Aggregates are deliberately independent Redis operations.
        return Mono.when(redis.execute(RECORD, List.of(keys.bindingHealth(binding)), args).then(),
                redis.execute(RECORD, List.of(keys.providerHealth(provider)), args).then(),
                redis.execute(RECORD, List.of(keys.modelHealth(model)), args).then());
    }
    private Mono<HealthSnapshot> read(String key) {
        return redis.<String, String>opsForHash().entries(key).collectMap(Map.Entry::getKey, Map.Entry::getValue).map(values -> {
            if (values.isEmpty() || clock.millis() - number(values, "window") >= 900_000) return HealthSnapshot.unknown();
            long successes = number(values, "success"), failures = number(values, "failure"), samples = successes + failures;
            HealthStatus status = samples < 5 ? HealthStatus.UNKNOWN : failures > successes ? HealthStatus.UNHEALTHY
                    : failures > 0 ? HealthStatus.DEGRADED : HealthStatus.HEALTHY;
            return new HealthSnapshot(status, successes, failures, number(values, "timeout"), number(values, "consecutive"),
                    decimal(values, "latency", 0), instant(values, "lastSuccess"), instant(values, "lastFailure"), number(values, "rateLimited"),
                    number(values, "cancelled"), decimal(values, "ttft", 0), number(values, "ttftSamples"), decimal(values, "ewma", 0.5),
                    decimal(values, "penalty", 0), instant(values, "sampled"), false, number(values, "nonStreamingSamples"), decimal(values, "fullLatency", 0));
        });
    }
    private static long number(Map<String, String> values, String key) { return Long.parseLong(values.getOrDefault(key, "0")); }
    private static double decimal(Map<String, String> values, String key, double fallback) { return Double.parseDouble(values.getOrDefault(key, Double.toString(fallback))); }
    private static Instant instant(Map<String, String> values, String key) { return values.containsKey(key) ? Instant.ofEpochMilli(number(values, key)) : null; }
}
