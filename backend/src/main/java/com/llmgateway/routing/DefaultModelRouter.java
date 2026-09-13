package com.llmgateway.routing;

import com.llmgateway.health.*;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import com.llmgateway.protocol.*;
import com.llmgateway.resilience.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DefaultModelRouter implements ModelRouter {
    private final ConfigurationStore configurations;
    private final TranslationValidator translation;
    private final HealthManager health;
    private final CircuitBreaker circuits;
    private final PreferredBindingStore preferences;
    private final Clock clock;
    public DefaultModelRouter(ConfigurationStore configurations, TranslationValidator translation, HealthManager health,
                              CircuitBreaker circuits, PreferredBindingStore preferences, Clock clock) {
        this.configurations = configurations; this.translation = translation; this.health = health;
        this.circuits = circuits; this.preferences = preferences; this.clock = clock;
    }
    @Override public Mono<RoutingDecision> route(LlmRequest request, RequestContext context) {
        return preview(request.model(), context.protocol(), RequestCapabilityExtractor.extract(request)).flatMap(decision -> {
            if (decision.candidateBindings().isEmpty()) {
                boolean incompatible = decision.evaluation().stream().anyMatch(v -> v.reason().equals("CAPABILITY_UNSUPPORTED") || v.reason().equals("PROTOCOL_UNSUPPORTED"));
                Set<ModelCapability> missing = decision.evaluation().stream().flatMap(v -> v.missingCapabilities().stream()).collect(java.util.stream.Collectors.toSet());
                return Mono.error(incompatible ? GatewayException.capabilities(missing) : new GatewayException(GatewayError.NO_CANDIDATES));
            }
            if (decision.configuration().policy().strategy() != RoutingStrategy.ADAPTIVE) return Mono.just(decision);
            return preferences.explore(decision.configuration().virtualModel().id()).map(count -> {
                if (count % 20 != 0) return decision;
                var exploring = decision.candidateBindings().stream().skip(1).filter(c -> decision.evaluation().stream()
                        .anyMatch(v -> v.bindingId().equals(c.binding().id()) && !v.score().sampled())).findFirst();
                if (exploring.isEmpty()) return decision;
                var candidates = new ArrayList<>(decision.candidateBindings()); candidates.remove(exploring.get()); candidates.addFirst(exploring.get());
                return new RoutingDecision(decision.configuration(), candidates, decision.evaluation(), "ADAPTIVE_EXPLORATION");
            }).onErrorMap(e -> e instanceof GatewayException ? e : new GatewayException(GatewayError.STORAGE_UNAVAILABLE));
        });
    }
    public Mono<RoutingDecision> preview(String name, Protocol source, Set<ModelCapability> required) {
        if (source == null || required == null) return Mono.error(GatewayException.invalid());
        return configurations.load(name).flatMap(configuration -> Flux.fromIterable(configuration.candidates())
                .concatMap(candidate -> evaluate(configuration, candidate, source, required)).collectList()
                .flatMap(evaluations -> {
                    List<CandidateView> views = new ArrayList<>(evaluations);
                    Comparator<CandidateView> order = Comparator.comparingInt(CandidateView::priority).thenComparing(CandidateView::bindingId);
                    if (configuration.policy().strategy() != RoutingStrategy.PRIORITY) order = Comparator.comparingDouble(CandidateView::sortScore).reversed().thenComparing(order);
                    views.sort(order);
                    List<RoutingCandidate> eligible = views.stream().filter(v -> v.reason().equals("ELIGIBLE"))
                            .map(v -> configuration.candidates().stream().filter(c -> c.binding().id().equals(v.bindingId())).findFirst().orElseThrow()).toList();
                    if (configuration.policy().strategy() != RoutingStrategy.ADAPTIVE || eligible.isEmpty()) {
                        return Mono.just(new RoutingDecision(configuration, eligible, views, configuration.policy().strategy().name()));
                    }
                    return preferences.read(configuration.virtualModel().id()).map(preference -> {
                        var candidates = new ArrayList<>(eligible);
                        candidates.stream().filter(c -> c.binding().id().equals(preference.bindingId())
                                && configuration.fingerprint(c).equals(preference.configurationVersion())).findFirst().ifPresent(preferred -> {
                            CandidateView best = views.stream().filter(v -> v.bindingId().equals(candidates.getFirst().binding().id())).findFirst().orElseThrow();
                            CandidateView current = views.stream().filter(v -> v.bindingId().equals(preferred.binding().id())).findFirst().orElseThrow();
                            var policy = configuration.policy().adaptive();
                            if (clock.millis() - preference.changedAt() < policy.minimumHoldMs() || best.sortScore() - current.sortScore() < policy.switchThreshold()) {
                                candidates.remove(preferred); candidates.addFirst(preferred);
                            }
                        });
                        return new RoutingDecision(configuration, candidates, views, "ADAPTIVE");
                    }).defaultIfEmpty(new RoutingDecision(configuration, eligible, views, "ADAPTIVE"));
                })).onErrorMap(e -> e instanceof GatewayException ? e : new GatewayException(GatewayError.STORAGE_UNAVAILABLE));
    }
    private Mono<CandidateView> evaluate(ConfigurationSnapshot snapshot, RoutingCandidate c, Protocol source, Set<ModelCapability> required) {
        String reason = ConfigurationEligibility.reason(c, source, translation);
        Set<ModelCapability> effective = CapabilityChecker.effective(c.model().capabilities(), c.binding().capabilitiesOverride(), translation.capabilities(source, c.binding().targetProtocol()));
        Set<ModelCapability> missing = CapabilityChecker.missing(required, effective);
        if (reason.equals("ELIGIBLE") && !missing.isEmpty()) reason = "CAPABILITY_UNSUPPORTED";
        final String configuredReason = reason;
        return Mono.zip(health.binding(c.binding().id()), circuits.snapshot(c.binding().id())).map(runtime -> {
            HealthSnapshot h = runtime.getT1(); CircuitSnapshot circuit = runtime.getT2();
            String filtered = configuredReason;
            if (filtered.equals("ELIGIBLE") && !circuit.available(clock.instant(), snapshot.fingerprint(c))) filtered = "CIRCUIT_UNAVAILABLE";
            boolean streaming = required.contains(ModelCapability.STREAMING);
            var score = AdaptiveBindingScorer.score(h, streaming, snapshot.policy().adaptive(), clock.instant());
            double sorted = switch (snapshot.policy().strategy()) {
                case PRIORITY -> -c.binding().priority();
                case LATENCY -> score.latency();
                case HEALTH -> score.success() + score.health() - score.penalty();
                case ADAPTIVE -> score.total();
            };
            return new CandidateView(c.binding().id(), c.provider().id(), c.model().id(), c.binding().priority(), filtered, missing, effective, sorted, score, h, circuit);
        });
    }
    public record CandidateView(String bindingId, String providerId, String providerModelId, int priority, String reason,
                                Set<ModelCapability> missingCapabilities, Set<ModelCapability> effectiveCapabilities, double sortScore,
                                AdaptiveBindingScorer.Score score, HealthSnapshot health, CircuitSnapshot circuit) {}
}
