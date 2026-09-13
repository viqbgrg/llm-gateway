package com.llmgateway.routing;

import com.llmgateway.infrastructure.RedisKeyNamespace;
import com.llmgateway.model.AdaptivePolicy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PreferredBindingStore {
    private static final RedisScript<Long> UPDATE = RedisScript.of("""
            local current = redis.call('HGET', KEYS[1], 'binding')
            local sampled = tonumber(redis.call('HGET', KEYS[1], 'sampled') or '0')
            if sampled > tonumber(ARGV[8]) then return 0 end
            if current and current ~= ARGV[1] then
                local held = tonumber(ARGV[4]) - tonumber(redis.call('HGET', KEYS[1], 'changed') or '0')
                if held < tonumber(ARGV[6]) then return 0 end
                if tonumber(ARGV[3]) < tonumber(redis.call('HGET', KEYS[1], 'score') or '0') + tonumber(ARGV[7]) then return 0 end
            end
            if current ~= ARGV[1] then redis.call('HSET', KEYS[1], 'changed', ARGV[4]) end
            redis.call('HSET', KEYS[1], 'binding', ARGV[1], 'version', ARGV[2], 'score', ARGV[3], 'sampled', ARGV[8])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);
    private static final RedisScript<Long> INVALIDATE = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'binding') == ARGV[1] and redis.call('HGET', KEYS[1], 'version') == ARGV[2] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final RedisScript<Long> EXPLORE = RedisScript.of("local n = redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], 300); return n", Long.class);
    private static final RedisScript<Long> SCORE = RedisScript.of("""
            local previous = tonumber(redis.call('HGET', KEYS[1], 'sampled') or '0')
            if previous > tonumber(ARGV[1]) then return 0 end
            redis.call('HSET', KEYS[1], 'sampled', ARGV[1], 'version', ARGV[2], 'score', ARGV[3]); redis.call('PEXPIRE', KEYS[1], ARGV[4]); return 1
            """, Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final RedisKeyNamespace keys;
    private final Clock clock;
    public PreferredBindingStore(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys, Clock clock) { this.redis = redis; this.keys = keys; this.clock = clock; }
    public Mono<Preference> read(String virtualModelId) {
        return redis.<String, String>opsForHash().entries(keys.preferredVirtualModel(virtualModelId)).collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(m -> !m.isEmpty()).map(m -> new Preference(m.get("binding"), m.get("version"), Double.parseDouble(m.get("score")), Long.parseLong(m.get("changed"))));
    }
    public Mono<Void> update(String virtualModel, String binding, String version, double score, AdaptivePolicy policy, long attemptStartedAt) {
        return redis.execute(UPDATE, List.of(keys.preferredVirtualModel(virtualModel)), List.of(binding, version, Double.toString(score),
                Long.toString(clock.millis()), Long.toString(policy.preferenceTtlMs()), Long.toString(policy.minimumHoldMs()), Double.toString(policy.switchThreshold()),
                Long.toString(attemptStartedAt))).then();
    }
    public Mono<Long> explore(String virtualModel) { return redis.execute(EXPLORE, List.of(keys.exploration(virtualModel)), List.of()).next(); }
    public Mono<Void> score(String binding, String version, AdaptiveBindingScorer.Score score, long sampledAt, long ttlMs) {
        return redis.execute(SCORE, List.of(keys.bindingScore(binding)), List.of(Long.toString(sampledAt), version,
                com.llmgateway.protocol.ProtocolJson.MAPPER.valueToTree(score).toString(), Long.toString(ttlMs))).then();
    }
    public Mono<Void> invalidate(String virtualModel, String binding, String version) {
        return redis.execute(INVALIDATE, List.of(keys.preferredVirtualModel(virtualModel)), List.of(binding, version)).then();
    }
    public record Preference(String bindingId, String configurationVersion, double score, long changedAt) {}
}
