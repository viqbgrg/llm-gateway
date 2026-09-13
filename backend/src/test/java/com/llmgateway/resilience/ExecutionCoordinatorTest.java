package com.llmgateway.resilience;

import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import com.llmgateway.routing.RoutingCandidate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import static org.assertj.core.api.Assertions.*;

class ExecutionCoordinatorTest {
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final HedgedRequestExecutor executor = new HedgedRequestExecutor(scheduler);
    private final List<RoutingCandidate> candidates = List.of(candidate("a", 1), candidate("b", 1), candidate("c", 1));
    @Test void aFastFirstSuccessDoesNotStartDelayedHedges() {
        AttemptBudget budget = budget(3); AtomicInteger starts = new AtomicInteger();
        var flux = executor.execute(candidates, policy(true, 2), budget, ignored -> true, (candidate, kind, cancellation) ->
                Mono.delay(Duration.ofMillis(10), scheduler).map(t -> candidate.binding().id()).doOnSubscribe(s -> { budget.acquire(); starts.incrementAndGet(); }).flux());
        StepVerifier.withVirtualTime(() -> flux, () -> scheduler, Long.MAX_VALUE).thenAwait(Duration.ofMillis(20)).expectNext("a").verifyComplete();
        assertThat(starts.get()).isEqualTo(1);
    }
    @Test void theThirdCandidateCanWinAndCancelsBothLosers() {
        AttemptBudget budget = budget(3); AtomicInteger cancelled = new AtomicInteger();
        var flux = executor.execute(candidates, policy(true, 2), budget, ignored -> true, (candidate, kind, cancellation) ->
                Mono.delay(Duration.ofMillis(candidate.binding().id().equals("c") ? 10 : 5000), scheduler)
                        .map(t -> candidate.binding().id()).doOnSubscribe(s -> budget.acquire()).doOnCancel(cancelled::incrementAndGet).flux());
        StepVerifier.withVirtualTime(() -> flux, () -> scheduler, Long.MAX_VALUE).thenAwait(Duration.ofMillis(250)).expectNext("c").verifyComplete();
        assertThat(budget.used()).isEqualTo(3); assertThat(cancelled.get()).isEqualTo(2);
    }
    @Test void onlyMeaningfulContentSelectsAStreamingWinnerWithOneSubscription() {
        AttemptBudget budget = budget(3); AtomicInteger subscribed = new AtomicInteger(); AtomicInteger cancelled = new AtomicInteger();
        var flux = executor.execute(candidates.subList(0, 2), policy(true, 1), budget, value -> value.startsWith("content"), (candidate, kind, cancellation) ->
                Flux.just("meta-" + candidate.binding().id()).concatWith(Mono.delay(Duration.ofMillis(candidate.binding().id().equals("a") ? 5000 : 1), scheduler)
                        .map(t -> "content-" + candidate.binding().id())).doOnSubscribe(s -> { budget.acquire(); subscribed.incrementAndGet(); }).doOnCancel(cancelled::incrementAndGet));
        StepVerifier.withVirtualTime(() -> flux, () -> scheduler, Long.MAX_VALUE).thenAwait(Duration.ofMillis(110)).expectNext("meta-b", "content-b").verifyComplete();
        assertThat(subscribed.get()).isEqualTo(2); assertThat(cancelled.get()).isEqualTo(1);
    }
    @Test void retriesAndFallbackShareTheGlobalBudgetWithoutRepeatingExhaustedCandidates() {
        AttemptBudget budget = budget(3); List<String> starts = new ArrayList<>();
        var flux = executor.<String>execute(candidates, policy(false, 0), budget, ignored -> true, (candidate, kind, cancellation) -> Flux.defer(() -> {
            budget.acquire(); starts.add(candidate.binding().id() + ":" + kind);
            return Flux.error(new GatewayException(GatewayError.PROVIDER_ERROR));
        }));
        StepVerifier.withVirtualTime(() -> flux, () -> scheduler, Long.MAX_VALUE).thenAwait(Duration.ofSeconds(1)).expectError(GatewayException.class).verify();
        assertThat(starts).containsExactly("a:FIRST", "a:RETRY", "b:FALLBACK"); assertThat(budget.used()).isEqualTo(3);
    }
    @Test void aFailedBranchDoesNotPreventAnAlreadyRunningCandidateFromSucceeding() {
        AttemptBudget budget = budget(3);
        var flux = executor.execute(candidates.subList(0, 2), policy(true, 1), budget, ignored -> true, (candidate, kind, cancellation) -> {
            budget.acquire();
            return candidate.binding().id().equals("a")
                    ? Mono.delay(Duration.ofMillis(150), scheduler).thenMany(Flux.<String>error(new GatewayException(GatewayError.PROVIDER_REQUEST_REJECTED)))
                    : Mono.delay(Duration.ofMillis(100), scheduler).thenMany(Flux.just("b"));
        });
        StepVerifier.withVirtualTime(() -> flux, () -> scheduler, Long.MAX_VALUE).thenAwait(Duration.ofMillis(210)).expectNext("b").verifyComplete();
    }
    @Test void aSlowConsumerCanRequestOneEventAtATimeWithoutOverflowOrResubscription() {
        AttemptBudget budget = budget(3); AtomicInteger subscriptions = new AtomicInteger();
        var flux = executor.execute(candidates, policy(false, 0), budget, value -> value.startsWith("content"), (candidate, kind, cancellation) ->
                Flux.just("meta", "content", "tail", "end").doOnSubscribe(s -> { budget.acquire(); subscriptions.incrementAndGet(); }));
        StepVerifier.create(flux, 0).thenRequest(1).expectNext("meta").thenRequest(1).expectNext("content")
                .thenRequest(1).expectNext("tail").thenRequest(1).expectNext("end").verifyComplete();
        assertThat(subscriptions.get()).isEqualTo(1);
    }
    @Test void cancellationStopsPendingHedgeTimersAndUpstreamSubscriptions() {
        AttemptBudget budget = budget(3); AtomicInteger cancelled = new AtomicInteger();
        var flux = executor.<String>execute(candidates, policy(true, 2), budget, ignored -> true, (candidate, kind, cancellation) -> {
            budget.acquire(); return Flux.<String>never().doOnCancel(cancelled::incrementAndGet);
        });
        StepVerifier.withVirtualTime(() -> flux, () -> scheduler, Long.MAX_VALUE).thenAwait(Duration.ofMillis(50)).thenCancel().verify();
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        assertThat(budget.used()).isEqualTo(1); assertThat(cancelled.get()).isEqualTo(1);
    }
    @Test void budgetsAreAtomicAndReplayRulesDistinguishUnsentAndSentRequests() {
        var budget = budget(3);
        long acquired = java.util.stream.IntStream.range(0, 100).parallel().filter(i -> { try { budget.acquire(); return true; } catch (GatewayException e) { return false; } }).count();
        assertThat(acquired).isEqualTo(3);
        var defaults = ExecutionPolicy.defaults();
        assertThat(RetryPolicy.retry(new GatewayException(GatewayError.CONNECTION_FAILED, false, Duration.ZERO), defaults)).isTrue();
        assertThat(RetryPolicy.retry(new GatewayException(GatewayError.NETWORK_ERROR), defaults)).isFalse();
        assertThat(RetryPolicy.fallback(new GatewayException(GatewayError.INVALID_RESPONSE), defaults)).isFalse();
        assertThat(RetryPolicy.fallback(new GatewayException(GatewayError.PROVIDER_AUTHENTICATION), defaults)).isTrue();
        assertThat(RetryPolicy.delay(10, new GatewayException(GatewayError.RATE_LIMITED, true, Duration.ofHours(1)), defaults, 1)).isEqualTo(Duration.ofSeconds(60));
    }
    private AttemptBudget budget(int max) { return new AttemptBudget(max, Instant.EPOCH.plusSeconds(10), clock); }
    private RoutingPolicy policy(boolean hedging, int hedges) {
        return new RoutingPolicy("p", "policy", RoutingStrategy.PRIORITY, hedging, Duration.ofMillis(100), hedges,
                new ExecutionPolicy(10000, 3, true, 1, 1, 0, 8, 30000, 1), AdaptivePolicy.defaults(), 1);
    }
    private RoutingCandidate candidate(String id, int retries) {
        Provider p = new Provider(id, id, "http://fixture.invalid", null, true, Protocol.CHAT_COMPLETIONS, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(10), retries, false, null, Duration.ofMinutes(30), Instant.EPOCH, Instant.EPOCH);
        ProviderModel m = new ProviderModel("m" + id, id, "actual", null, ProviderModelStatus.ACTIVE, new ModelCapabilities(Set.of(ModelCapability.CHAT)), null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
        VirtualModelBinding b = new VirtualModelBinding(id, "virtual", id, "m" + id, true, 0, false, Protocol.CHAT_COMPLETIONS, Protocol.CHAT_COMPLETIONS, null, Instant.EPOCH, Instant.EPOCH);
        return new RoutingCandidate(b, p, m, 1, 1, 1);
    }
}
