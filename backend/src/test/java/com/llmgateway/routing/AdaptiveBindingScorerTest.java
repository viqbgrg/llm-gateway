package com.llmgateway.routing;

import com.llmgateway.health.HealthSnapshot;
import com.llmgateway.health.HealthStatus;
import com.llmgateway.model.AdaptivePolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdaptiveBindingScorerTest {
    private final AdaptivePolicy policy = AdaptivePolicy.defaults();
    private final Instant now = Instant.parse("2026-09-06T00:00:00Z");

    @Test void unknownAndInsufficientSamplesHaveNeutralPriors() {
        var unknown = AdaptiveBindingScorer.score(HealthSnapshot.unknown(), false, policy, now);
        var few = AdaptiveBindingScorer.score(snapshot(2, 1, 1, 0, false), true, policy, now);
        assertThat(unknown.total()).isEqualTo(0.5);
        assertThat(few.total()).isEqualTo(unknown.total());
        assertThat(few.sampled()).isFalse();
    }

    @Test void streamingAndNonStreamingUseDifferentLatencySamples() {
        var h = snapshot(10, 5000, 10, 0, false);
        var stream = AdaptiveBindingScorer.score(h, true, policy, now);
        var complete = AdaptiveBindingScorer.score(h, false, policy, now);
        assertThat(stream.latency()).isCloseTo(1.0 / 1.01, within(0.000001));
        assertThat(complete.latency()).isCloseTo(1.0 / 6, within(0.000001));
        assertThat(stream.total()).isGreaterThan(complete.total());
        var onlyNonStreaming = new HealthSnapshot(HealthStatus.HEALTHY, 10, 0, 0, 0, 100,
                now, null, 0, 0, 0, 0, 1, 0, now, false, 10, 100);
        assertThat(AdaptiveBindingScorer.score(onlyNonStreaming, true, policy, now).latency()).isEqualTo(0.5);
    }

    @Test void penaltiesDecayByTheConfiguredHalfLifeAndExpiredSamplesAreIgnored() {
        var h = snapshot(10, 100, 10, 2, false);
        var current = AdaptiveBindingScorer.score(h, false, policy, now);
        var later = AdaptiveBindingScorer.score(h, false, policy, now.plusMillis(policy.penaltyHalfLifeMs()));
        assertThat(current.penalty()).isEqualTo(2);
        assertThat(later.penalty()).isEqualTo(1);
        assertThat(later.total()).isGreaterThan(current.total());
        assertThat(AdaptiveBindingScorer.score(snapshot(10, 100, 10, 2, true), false, policy, now).total()).isEqualTo(0.5);
    }

    private HealthSnapshot snapshot(long samples, double latency, double ttft, double penalty, boolean expired) {
        return new HealthSnapshot(HealthStatus.HEALTHY, samples, 0, 0, 0, latency, now, null, 0, 0,
                ttft, samples, 1, penalty, now, expired, samples, latency);
    }
}
