package com.llmgateway.resilience;

import com.llmgateway.health.AttemptOutcome;
import com.llmgateway.infrastructure.RedisKeyNamespace;
import com.llmgateway.inference.*;
import com.llmgateway.model.ExecutionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisCircuitBreaker implements CircuitBreaker {
    private static final RedisScript<String> ACQUIRE = RedisScript.of("""
            local now = tonumber(ARGV[1])
            local oldVersion = redis.call('HGET', KEYS[1], 'version')
            if oldVersion and oldVersion ~= ARGV[2] then
                local old = {}; for number in string.gmatch(oldVersion, '%d+') do table.insert(old, tonumber(number)) end
                local index = 1
                for number in string.gmatch(ARGV[2], '%d+') do
                    if tonumber(number) < (old[index] or 0) then return 'DENIED' end
                    if tonumber(number) > (old[index] or 0) then break end
                    index = index + 1
                end
            end
            if oldVersion ~= ARGV[2] then
                redis.call('DEL', KEYS[1]); redis.call('HSET', KEYS[1], 'version', ARGV[2], 'state', 'CLOSED', 'generation', 0)
            end
            local values = redis.call('HGETALL', KEYS[1]); local permits = 0
            for i=1,#values,2 do
                if string.sub(values[i],1,7) == 'permit:' then
                    if tonumber(values[i+1]) <= now then redis.call('HDEL', KEYS[1], values[i]) else permits = permits + 1 end
                end
            end
            if redis.call('HGET', KEYS[1], 'blocked') == '1' or tonumber(redis.call('HGET', KEYS[1], 'rateUntil') or '0') > now then return 'DENIED' end
            local state = redis.call('HGET', KEYS[1], 'state')
            local transition = ''
            if state == 'OPEN' then
                if tonumber(redis.call('HGET', KEYS[1], 'openUntil') or '0') > now then return 'DENIED' end
                state = 'HALF_OPEN'; permits = 0
                transition = 'OPEN>HALF_OPEN'
                for i=1,#values,2 do if string.sub(values[i],1,7) == 'permit:' then redis.call('HDEL', KEYS[1], values[i]) end end
                redis.call('HSET', KEYS[1], 'state', state, 'recovered', 0)
                redis.call('HINCRBY', KEYS[1], 'generation', 1)
            end
            if state == 'HALF_OPEN' and permits >= tonumber(ARGV[5]) then return 'DENIED' end
            redis.call('HSET', KEYS[1], 'permit:' .. ARGV[3], now + tonumber(ARGV[4]), 'updated', now)
            redis.call('EXPIRE', KEYS[1], 7200)
            return redis.call('HGET', KEYS[1], 'generation') .. '|' .. transition
            """, String.class);
    private static final RedisScript<String> SETTLE = RedisScript.of("""
            local permit = 'permit:' .. ARGV[3]
            if redis.call('HGET', KEYS[1], 'version') ~= ARGV[2] then return '' end
            if redis.call('HDEL', KEYS[1], permit) == 0 then return '' end
            if redis.call('HGET', KEYS[1], 'generation') ~= ARGV[4] then return '' end
            local now = tonumber(ARGV[1]); local kind = ARGV[5]; local state = redis.call('HGET', KEYS[1], 'state')
            if kind == 'SUCCESS' then
                if state == 'HALF_OPEN' then
                    local successes = redis.call('HINCRBY', KEYS[1], 'recovered', 1)
                    if successes >= tonumber(ARGV[8]) then
                        redis.call('HSET', KEYS[1], 'state', 'CLOSED', 'failures', 0)
                        redis.call('HINCRBY', KEYS[1], 'generation', 1)
                    end
                elseif state == 'CLOSED' then redis.call('HSET', KEYS[1], 'failures', 0) end
            elseif ARGV[9] == '1' then
                local failures = redis.call('HINCRBY', KEYS[1], 'failures', 1)
                if state == 'HALF_OPEN' or failures >= tonumber(ARGV[6]) then
                    redis.call('HSET', KEYS[1], 'state', 'OPEN', 'openUntil', now + tonumber(ARGV[7]))
                    redis.call('HINCRBY', KEYS[1], 'generation', 1)
                end
            elseif kind == 'RATE_LIMITED' then redis.call('HSET', KEYS[1], 'rateUntil', now + tonumber(ARGV[10]))
            elseif kind == 'AUTHENTICATION_ERROR' then redis.call('HSET', KEYS[1], 'blocked', 1) end
            redis.call('HSET', KEYS[1], 'updated', now)
            redis.call('EXPIRE', KEYS[1], 7200)
            local nextState = redis.call('HGET', KEYS[1], 'state')
            if state ~= nextState then return state .. '>' .. nextState end
            return ''
            """, String.class);
    private final ReactiveStringRedisTemplate redis;
    private final RedisKeyNamespace keys;
    private final Clock clock;
    private final com.llmgateway.infrastructure.GatewayMetrics metrics;
    public RedisCircuitBreaker(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys, Clock clock) { this(redis, keys, clock, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public RedisCircuitBreaker(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys, Clock clock, com.llmgateway.infrastructure.GatewayMetrics metrics) { this.redis = redis; this.keys = keys; this.clock = clock; this.metrics = metrics; }
    @Override public Mono<CircuitSnapshot> snapshot(String id) {
        return redis.<String, String>opsForHash().entries(keys.bindingCircuit(id)).collectMap(Map.Entry::getKey, Map.Entry::getValue).map(m -> {
            if (m.isEmpty()) return CircuitSnapshot.unknown();
            long now = clock.millis();
            int active = (int) m.entrySet().stream().filter(e -> e.getKey().startsWith("permit:") && Long.parseLong(e.getValue()) > now).count();
            CircuitState state = CircuitState.valueOf(m.getOrDefault("state", "CLOSED"));
            Instant openUntil = instant(m, "openUntil");
            if (state == CircuitState.OPEN && openUntil != null && !openUntil.isAfter(clock.instant())) {
                state = CircuitState.HALF_OPEN;
                active = 0; // Acquire discards permits from the previous circuit generation before allowing probes.
            }
            return new CircuitSnapshot(state, openUntil, instant(m, "rateUntil"), active, m.get("version"), "1".equals(m.get("blocked")), instant(m, "updated"), false);
        }).doOnNext(state -> { if (metrics != null) metrics.circuitState(id, state.state()); }).onErrorMap(e -> new GatewayException(GatewayError.STORAGE_UNAVAILABLE));
    }
    @Override public Mono<Permit> acquire(String id, String version, String attempt, Duration lifetime, ExecutionPolicy policy) {
        return redis.execute(ACQUIRE, List.of(keys.bindingCircuit(id)), List.of(Long.toString(clock.millis()), version, attempt,
                Long.toString(Math.max(1, lifetime.toMillis()) + 5000), Integer.toString(policy.halfOpenPermits())))
                .next().flatMap(generation -> generation.equals("DENIED") ? Mono.error(new GatewayException(GatewayError.NO_CANDIDATES))
                        : Mono.fromSupplier(() -> {
                            String[] parts = generation.split("\\|", -1);
                            if (metrics != null) metrics.circuitTransition(parts[1]);
                            return new Permit(id, attempt, version, Long.parseLong(parts[0]));
                        }))
                .onErrorMap(e -> e instanceof GatewayException ? e : new GatewayException(GatewayError.STORAGE_UNAVAILABLE));
    }
    @Override public Mono<Void> settle(Permit p, AttemptOutcome outcome, ExecutionPolicy policy, Duration retryAfter) {
        return redis.execute(SETTLE, List.of(keys.bindingCircuit(p.bindingId())), List.of(Long.toString(clock.millis()), p.configurationVersion(),
                p.attemptId(), Long.toString(p.generation()), outcome.name(), Integer.toString(policy.failureThreshold()), Long.toString(policy.cooldownMs()),
                Integer.toString(policy.halfOpenPermits()), outcome.failure() ? "1" : "0", Long.toString(Math.min(60_000, Math.max(1000, retryAfter.toMillis())))))
                .doOnNext(transition -> { if (metrics != null) metrics.circuitTransition(transition); }).then();
    }
    private static Instant instant(Map<String, String> m, String key) { return m.containsKey(key) ? Instant.ofEpochMilli(Long.parseLong(m.get(key))) : null; }
}
