package com.llmgateway.health;

import com.llmgateway.admin.*;
import com.llmgateway.model.ModelCapability;
import com.llmgateway.protocol.TranslationValidator;
import com.llmgateway.resilience.*;
import com.llmgateway.routing.*;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RuntimeHealthService {
    private final BindingRepository bindings;
    private final ProviderRepository providers;
    private final ProviderModelRepository models;
    private final ConfigurationStore configurations;
    private final TranslationValidator translation;
    private final HealthManager health;
    private final CircuitBreaker circuits;
    private final PreferredBindingStore preferences;
    private final DashboardStore dashboard;
    private final Clock clock;

    public RuntimeHealthService(BindingRepository bindings, ProviderRepository providers, ProviderModelRepository models,
                                ConfigurationStore configurations, TranslationValidator translation, HealthManager health,
                                CircuitBreaker circuits, PreferredBindingStore preferences, DashboardStore dashboard, Clock clock) {
        this.bindings = bindings; this.providers = providers; this.models = models; this.configurations = configurations;
        this.translation = translation; this.health = health; this.circuits = circuits; this.preferences = preferences;
        this.dashboard = dashboard; this.clock = clock;
    }

    public Mono<HealthView> all() {
        return Mono.zip(bindingViews().collectList(),
                providers.findAll().concatMap(p -> health.provider(p.id()).map(h -> new AggregateView(p.id(), p.enabled() ? "ENABLED" : "DISABLED", h))).collectList(),
                models.findAll().concatMap(m -> health.model(m.id()).map(h -> new AggregateView(m.id(), m.status().name(), h))).collectList())
                .map(result -> new HealthView(result.getT1(), result.getT2(), result.getT3()));
    }

    public Mono<BindingView> binding(String id) {
        return bindings.findById(id).flatMap(b -> configurations.inspect(b.virtualModelId())
                        .flatMap(configuration -> Mono.justOrEmpty(configuration.candidates().stream()
                                        .filter(candidate -> candidate.binding().id().equals(id)).findFirst())
                                .flatMap(candidate -> binding(configuration, candidate))))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Binding not found")));
    }

    public Mono<DashboardView> dashboard() {
        return Mono.zip(dashboard.read(), bindingViews().collectList())
                .map(result -> new DashboardView(result.getT1(), result.getT2().stream().filter(BindingView::available).count(),
                        result.getT2().stream().filter(b -> b.circuit().state() == CircuitState.OPEN).count()));
    }

    private Flux<BindingView> bindingViews() {
        // One consistent configuration snapshot per virtual model, shared by all its bindings.
        return bindings.findAll().map(BindingEntity::virtualModelId).distinct().concatMap(id -> configurations.inspect(id)
                .flatMapMany(configuration -> Flux.fromIterable(configuration.candidates())
                        .flatMapSequential(candidate -> binding(configuration, candidate), 8))
                .onErrorResume(ResponseStatusException.class, error -> error.getStatusCode() == HttpStatus.NOT_FOUND
                        ? Flux.empty() : Flux.error(error)));
    }

    private Mono<BindingView> binding(ConfigurationSnapshot configuration, RoutingCandidate candidate) {
        var binding = candidate.binding();
        String version = configuration.fingerprint(candidate);
        return Mono.zip(health.binding(binding.id()), circuits.snapshot(binding.id()), preferences.read(binding.virtualModelId())
                .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())).map(runtime -> {
            CircuitSnapshot circuit = runtime.getT2();
            if (circuit.configurationVersion() != null && !circuit.configurationVersion().equals(version)) circuit = CircuitSnapshot.unknown();
            String reason = configuration.virtualModel().enabled()
                    ? ConfigurationEligibility.reason(candidate, binding.sourceProtocol(), translation) : "VIRTUAL_MODEL_DISABLED";
            if (reason.equals("ELIGIBLE") && !CapabilityChecker.effective(candidate.model().capabilities(), candidate.binding().capabilitiesOverride(),
                    translation.capabilities(binding.sourceProtocol(), binding.targetProtocol())).contains(ModelCapability.CHAT)) reason = "CAPABILITY_UNSUPPORTED";
            if (reason.equals("ELIGIBLE") && !circuit.available(clock.instant(), version)) reason = circuit.configurationBlocked() ? "CONFIGURATION_BLOCKED" : "CIRCUIT_UNAVAILABLE";
            if (reason.equals("ELIGIBLE") && circuit.state() == CircuitState.HALF_OPEN
                    && circuit.activePermits() >= configuration.policy().execution().halfOpenPermits()) reason = "PROBE_IN_USE";
            boolean available = reason.equals("ELIGIBLE");
            boolean preferred = available && configuration.policy().strategy() == com.llmgateway.model.RoutingStrategy.ADAPTIVE
                    && runtime.getT3().filter(p -> p.bindingId().equals(binding.id()) && p.configurationVersion().equals(version)).isPresent();
            return new BindingView(binding.id(), binding.virtualModelId(), binding.providerId(), binding.providerModelId(), binding.enabled(),
                    candidate.provider().enabled(), candidate.model().status().name(), configuration.virtualModel().enabled(), available, reason,
                    runtime.getT1(), circuit, preferred, preferred ? "ADAPTIVE_SCORE_AND_HOLD" : null);
        });
    }

    public record BindingView(String id, String virtualModelId, String providerId, String providerModelId, boolean enabled,
                              boolean providerEnabled, String modelStatus, boolean virtualModelEnabled, boolean available, String availabilityReason,
                              HealthSnapshot health, CircuitSnapshot circuit, boolean preferred, String preferredReason) {}
    public record AggregateView(String id, String configurationState, HealthSnapshot health) {}
    public record HealthView(List<BindingView> bindings, List<AggregateView> providers, List<AggregateView> models) {}
    public record DashboardView(DashboardStore.Dashboard traffic, long availableBindings, long openCircuits) {}
}
