package com.llmgateway.inference;

import com.llmgateway.config.InferenceProperties;
import com.llmgateway.health.*;
import com.llmgateway.infrastructure.GatewayMetrics;
import com.llmgateway.model.*;
import com.llmgateway.protocol.*;
import com.llmgateway.provider.*;
import com.llmgateway.resilience.*;
import com.llmgateway.routing.*;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class InferenceService {
    private final ModelRouter router;
    private final ConfigurationStore configurations;
    private final ProviderAdapter provider;
    private final CircuitBreaker circuits;
    private final HealthManager health;
    private final PreferredBindingStore preferences;
    private final HedgedRequestExecutor executor;
    private final GatewayMetrics metrics;
    private final InferenceProperties properties;
    private final Clock clock;
    private final DashboardStore dashboard;
    public InferenceService(ModelRouter router, ConfigurationStore configurations, ProviderAdapter provider, CircuitBreaker circuits,
                            HealthManager health, PreferredBindingStore preferences, HedgedRequestExecutor executor,
                            GatewayMetrics metrics, InferenceProperties properties, Clock clock, DashboardStore dashboard) {
        this.router = router; this.configurations = configurations; this.provider = provider; this.circuits = circuits; this.health = health;
        this.preferences = preferences; this.executor = executor; this.metrics = metrics; this.properties = properties; this.clock = clock;
        this.dashboard = dashboard;
    }
    public Mono<LlmResponse> execute(LlmRequest request, RequestContext context) {
        return router.route(request, context).flatMap(decision -> {
            ExecutionContext execution = context(request, context, decision);
            return executor.execute(decision.candidateBindings(), decision.configuration().policy(), execution.budget(), ignored -> true,
                    (candidate, kind, cancellation) -> single(request, execution, candidate, kind, cancellation).flux(),
                    kind -> { if (kind == HedgedRequestExecutor.Kind.HEDGE) metrics.hedgeWon(); }).single();
        }).timeout(remainingGlobal(context)).onErrorMap(this::safe);
    }
    public Flux<LlmStreamEvent> stream(LlmRequest request, RequestContext context) {
        return router.route(request, context).flatMapMany(decision -> {
            ExecutionContext execution = context(request, context, decision);
            return executor.execute(decision.candidateBindings(), decision.configuration().policy(), execution.budget(), ClientSseEncoder::meaningful,
                    (candidate, kind, cancellation) -> streaming(request, execution, candidate, kind, cancellation),
                    kind -> { if (kind == HedgedRequestExecutor.Kind.HEDGE) metrics.hedgeWon(); });
        }).takeUntilOther(Mono.delay(remainingGlobal(context)).flatMap(t -> Mono.error(new GatewayException(GatewayError.TIMEOUT))))
                .onErrorMap(this::safe);
    }
    private ExecutionContext context(LlmRequest request, RequestContext context, RoutingDecision decision) {
        long deadline = Math.min(properties.deadline().toMillis(), decision.configuration().policy().execution().deadlineMs());
        return new ExecutionContext(context, request.model(), decision.configuration(), new AttemptBudget(
                decision.configuration().policy().execution().maxTotalAttempts(), context.enteredAt().plusMillis(deadline), clock));
    }
    private Duration remainingGlobal(RequestContext context) {
        Duration value = Duration.between(clock.instant(), context.enteredAt().plus(properties.deadline()));
        return value.isNegative() || value.isZero() ? Duration.ofNanos(1) : value;
    }
    private Mono<CircuitBreaker.Permit> permit(ExecutionContext context, RoutingCandidate candidate, String attemptId) {
        return configurations.current(context.logicalModel(), context.configuration(), candidate)
                .flatMap(current -> current ? circuits.acquire(candidate.binding().id(), context.configuration().fingerprint(candidate), attemptId,
                        context.budget().remaining(), context.configuration().policy().execution()) : Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)));
    }
    private Mono<LlmResponse> single(LlmRequest request, ExecutionContext context, RoutingCandidate candidate, HedgedRequestExecutor.Kind kind,
                                     HedgedRequestExecutor.Cancellation cancellation) {
        return Mono.defer(() -> {
            AttemptState state = new AttemptState(context, candidate, kind);
            return Mono.usingWhen(permit(context, candidate, state.id),
                    permit -> provider.execute(request, state.providerContext()).doOnNext(response -> state.usage = response.usage()),
                    permit -> settle(state, permit, AttemptOutcome.SUCCESS, Duration.ZERO),
                    (permit, failure) -> settle(state, permit, outcome(failure), GatewayException.safe(failure).retryAfter()),
                    permit -> settle(state, permit, cancellation.outcome(), Duration.ZERO));
        });
    }
    private Flux<LlmStreamEvent> streaming(LlmRequest request, ExecutionContext context, RoutingCandidate candidate,
                                          HedgedRequestExecutor.Kind kind, HedgedRequestExecutor.Cancellation cancellation) {
        return Flux.defer(() -> {
            AttemptState state = new AttemptState(context, candidate, kind);
            state.streaming = true;
            return Flux.usingWhen(permit(context, candidate, state.id), permit -> provider.stream(request, state.providerContext()).doOnNext(event -> {
                if (state.ttft == null && ClientSseEncoder.content(event)) state.ttft = System.nanoTime() - state.started;
                if (event instanceof LlmStreamEvent.UsageUpdate u) state.usage = u.usage();
            }), permit -> settle(state, permit, AttemptOutcome.SUCCESS, Duration.ZERO),
                    (permit, failure) -> settle(state, permit, outcome(failure), GatewayException.safe(failure).retryAfter()),
                    permit -> settle(state, permit, cancellation.outcome(), Duration.ZERO));
        });
    }
    private Mono<Void> settle(AttemptState state, CircuitBreaker.Permit permit, AttemptOutcome outcome, Duration retryAfter) {
        if (!state.settled.compareAndSet(false, true)) return Mono.empty();
        var candidate = state.candidate;
        var configuration = state.context.configuration();
        long elapsed = Math.max(0, System.nanoTime() - state.started);
        Mono<Void> circuit = circuits.settle(permit, outcome, configuration.policy().execution(), retryAfter);
        if (!state.dispatched) return circuit.timeout(Duration.ofSeconds(1)).onErrorResume(ignored -> Mono.empty());
        metrics.attempt(state.context.request().requestId(), state.id, configuration.virtualModel().id(), candidate.binding().id(), candidate.provider().id(),
                state.context.request().protocol(), candidate.binding().targetProtocol(), state.streaming, state.kind, outcome, elapsed, state.firstTransportEvent, state.ttft, state.usage);
        Mono<Void> record = health.record(candidate.binding().id(), candidate.provider().id(), candidate.model().id(), outcome,
                TimeUnit.NANOSECONDS.toMillis(elapsed), state.ttft == null ? null : TimeUnit.NANOSECONDS.toMillis(state.ttft),
                state.streaming, configuration.policy().adaptive().penaltyHalfLifeMs());
        Mono<Void> preference = Mono.empty();
        if (configuration.policy().strategy() == RoutingStrategy.ADAPTIVE) {
            if (outcome == AttemptOutcome.SUCCESS) {
                preference = record.then(configurations.current(state.context.logicalModel(), configuration, candidate))
                        .filter(Boolean::booleanValue).flatMap(ignored -> health.binding(candidate.binding().id()))
                        .filter(h -> h.samples() >= configuration.policy().adaptive().minimumSamples())
                        .flatMap(h -> {
                            var score = AdaptiveBindingScorer.score(h, state.streaming, configuration.policy().adaptive(), clock.instant());
                            return preferences.score(candidate.binding().id(), configuration.fingerprint(candidate), score,
                                    h.sampledAt().toEpochMilli(), configuration.policy().adaptive().preferenceTtlMs())
                                    .then(preferences.update(configuration.virtualModel().id(), candidate.binding().id(), configuration.fingerprint(candidate),
                                            score.total(), configuration.policy().adaptive(), state.startedAt));
                        });
                record = Mono.empty();
            } else if (outcome.failure() || outcome == AttemptOutcome.AUTHENTICATION_ERROR) preference = preferences.invalidate(configuration.virtualModel().id(), candidate.binding().id(), configuration.fingerprint(candidate));
        }
        return Mono.whenDelayError(circuit, record, preference, dashboard.attempt(state.kind, outcome)).timeout(Duration.ofSeconds(1)).onErrorResume(ignored -> Mono.empty());
    }
    private GatewayException safe(Throwable error) {
        return error instanceof java.util.concurrent.TimeoutException ? new GatewayException(GatewayError.TIMEOUT) : GatewayException.safe(error);
    }
    private static AttemptOutcome outcome(Throwable failure) {
        return switch (GatewayException.safe(failure).error()) {
            case TIMEOUT -> AttemptOutcome.TIMEOUT;
            case RATE_LIMITED -> AttemptOutcome.RATE_LIMITED;
            case PROVIDER_AUTHENTICATION, PROVIDER_MODEL_NOT_FOUND, CREDENTIAL_CONFIGURATION -> AttemptOutcome.AUTHENTICATION_ERROR;
            case INVALID_RESPONSE -> AttemptOutcome.INVALID_RESPONSE;
            case PROVIDER_REQUEST_REJECTED, INVALID_REQUEST, CAPABILITY_UNSUPPORTED, CONFIGURATION_CHANGED, NO_CANDIDATES -> AttemptOutcome.REQUEST_ERROR;
            default -> AttemptOutcome.PROVIDER_ERROR;
        };
    }
    private final class AttemptState {
        final String id = UUID.randomUUID().toString();
        final ExecutionContext context;
        final RoutingCandidate candidate;
        final HedgedRequestExecutor.Kind kind;
        final AtomicBoolean settled = new AtomicBoolean();
        volatile long started = System.nanoTime();
        volatile long startedAt = clock.millis();
        volatile boolean dispatched;
        boolean streaming;
        volatile Long firstTransportEvent;
        volatile Long ttft;
        volatile Usage usage;
        AttemptState(ExecutionContext context, RoutingCandidate candidate, HedgedRequestExecutor.Kind kind) { this.context = context; this.candidate = candidate; this.kind = kind; }
        ProviderContext providerContext() {
            return new ProviderContext(candidate.provider(), context.request().requestId(), id, candidate.binding().id(), candidate.model().modelName(), context.budget().remaining(), () -> {
                context.budget().acquire(); dispatched = true; started = System.nanoTime(); startedAt = clock.millis();
            }, () -> { if (firstTransportEvent == null) firstTransportEvent = System.nanoTime() - started; });
        }
    }
}
