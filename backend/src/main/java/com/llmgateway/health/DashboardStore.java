package com.llmgateway.health;

import com.llmgateway.infrastructure.RedisKeyNamespace;
import com.llmgateway.model.Usage;
import com.llmgateway.resilience.HedgedRequestExecutor.Kind;
import java.time.Clock;
import java.util.*;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DashboardStore {
    public static final long[] LATENCY_BOUNDS_MS = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000, 600000};
    private static final RedisScript<Long> RECORD = RedisScript.of("""
            for i=1,#ARGV,2 do redis.call('HINCRBYFLOAT', KEYS[1], ARGV[i], ARGV[i+1]) end
            redis.call('EXPIRE', KEYS[1], 960)
            return 1
            """, Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final RedisKeyNamespace keys;
    private final Clock clock;
    public DashboardStore(ReactiveStringRedisTemplate redis, RedisKeyNamespace keys, Clock clock) { this.redis = redis; this.keys = keys; this.clock = clock; }
    public Mono<Void> request(String outcome, long durationMs, Usage usage) {
        List<String> args = new ArrayList<>(List.of("requests", "1", "duration", Long.toString(durationMs)));
        if (outcome.equals("SUCCESS")) args.addAll(List.of("success", "1"));
        int bucket = 0; while (bucket < LATENCY_BOUNDS_MS.length - 1 && durationMs > LATENCY_BOUNDS_MS[bucket]) bucket++;
        args.addAll(List.of("latency:" + bucket, "1"));
        if (usage != null) {
            if (usage.inputTokens() != null) args.addAll(List.of("inputTokens", usage.inputTokens().toString()));
            if (usage.outputTokens() != null) args.addAll(List.of("outputTokens", usage.outputTokens().toString()));
        }
        return record(args);
    }
    public Mono<Void> attempt(Kind kind, AttemptOutcome outcome) {
        List<String> args = new ArrayList<>(List.of("attempts", "1"));
        if (kind == Kind.RETRY) args.addAll(List.of("retries", "1"));
        if (kind == Kind.FALLBACK) args.addAll(List.of("fallbacks", "1"));
        if (kind == Kind.HEDGE) args.addAll(List.of("hedges", "1"));
        if (outcome.cancellation()) args.addAll(List.of("cancellations", "1"));
        return record(args);
    }
    private Mono<Void> record(List<String> args) {
        return redis.execute(RECORD, List.of(keys.dashboardMinute(clock.millis() / 60_000)), args).then();
    }
    public Mono<Dashboard> read() {
        long minute = clock.millis() / 60_000;
        return Flux.range(0, 15).concatMap(offset -> redis.<String, String>opsForHash().entries(keys.dashboardMinute(minute - offset))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue).map(values -> new Bucket((minute - offset) * 60_000,
                        number(values, "requests"), number(values, "success"), values)))
                .collectList().map(buckets -> {
                    Map<String, Double> totals = new HashMap<>();
                    buckets.forEach(b -> b.values().forEach((key, value) -> totals.merge(key, Double.parseDouble(value), Double::sum)));
                    long requests = totals.getOrDefault("requests", 0.0).longValue(); long success = totals.getOrDefault("success", 0.0).longValue();
                    Long p95 = null; long accumulated = 0;
                    for (int i = 0; i < LATENCY_BOUNDS_MS.length && requests > 0; i++) {
                        accumulated += totals.getOrDefault("latency:" + i, 0.0).longValue();
                        if (accumulated >= Math.ceil(requests * 0.95)) { p95 = LATENCY_BOUNDS_MS[i]; break; }
                    }
                    return new Dashboard(15, minute * 60_000 - 14 * 60_000, clock.millis(), requests, success,
                            requests == 0 ? null : (double) success / requests, requests == 0 ? null : totals.getOrDefault("duration", 0.0) / requests,
                            p95, total(totals, "attempts"), total(totals, "retries"), total(totals, "fallbacks"), total(totals, "hedges"),
                            total(totals, "cancellations"), total(totals, "inputTokens"), total(totals, "outputTokens"),
                            buckets.stream().map(b -> new Minute(b.startedAt(), b.requests(), b.successes())).sorted(Comparator.comparingLong(Minute::startedAt)).toList());
                });
    }
    private static long number(Map<String, String> m, String k) { return (long) Double.parseDouble(m.getOrDefault(k, "0")); }
    private static long total(Map<String, Double> m, String k) { return m.getOrDefault(k, 0.0).longValue(); }
    private record Bucket(long startedAt, long requests, long successes, Map<String, String> values) {}
    public record Minute(long startedAt, long requests, long successes) {}
    public record Dashboard(int windowMinutes, long from, long to, long requests, long successes, Double successRate,
                            Double averageLatencyMs, Long p95UpperBoundMs, long attempts, long retries, long fallbacks, long hedges,
                            long cancellations, long knownInputTokens, long knownOutputTokens, List<Minute> minutes) {}
}
