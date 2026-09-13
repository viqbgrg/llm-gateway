package com.llmgateway.routing;

import com.llmgateway.admin.*;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@Component
public class ConfigurationStore {
    private static final int LOOKUP_BATCH_SIZE = 256;
    private final VirtualModelResolver resolver;
    private final VirtualModelRepository virtualModels;
    private final BindingRepository bindings;
    private final ProviderRepository providers;
    private final ProviderModelRepository models;
    private final RoutingPolicyRepository policies;
    private final TransactionalOperator transaction;
    private final RoutingPolicy defaults;
    public ConfigurationStore(VirtualModelResolver resolver, VirtualModelRepository virtualModels, BindingRepository bindings, ProviderRepository providers,
                              ProviderModelRepository models, RoutingPolicyRepository policies, ReactiveTransactionManager manager,
                              com.llmgateway.config.RoutingProperties routing, com.llmgateway.config.InferenceProperties inference,
                              com.llmgateway.config.ResilienceProperties resilience) {
        this.resolver = resolver; this.virtualModels = virtualModels; this.bindings = bindings; this.providers = providers; this.models = models; this.policies = policies;
        var definition = new DefaultTransactionDefinition(); definition.setReadOnly(true);
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transaction = TransactionalOperator.create(manager, definition);
        this.defaults = routing.policy(inference, resilience);
    }
    public Mono<ConfigurationSnapshot> load(String name) {
        return resolver.resolve(name).flatMap(this::snapshot).as(transaction::transactional)
                .onErrorMap(DataAccessException.class, e -> new GatewayException(GatewayError.STORAGE_UNAVAILABLE));
    }
    /** Read disabled configuration as well, without acquiring runtime permits or changing preferences. */
    public Mono<ConfigurationSnapshot> inspect(String virtualModelId) {
        return virtualModels.findById(virtualModelId).switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Virtual model not found")))
                .flatMap(this::snapshot).as(transaction::transactional)
                .onErrorMap(DataAccessException.class, e -> new GatewayException(GatewayError.STORAGE_UNAVAILABLE));
    }
    private Mono<ConfigurationSnapshot> snapshot(VirtualModelEntity vm) {
        return (vm.routingPolicyId() == null ? Mono.just(defaults)
                : policies.findById(vm.routingPolicyId()).map(RoutingPolicyEntity::domain)
                    .switchIfEmpty(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED))))
                .flatMap(policy -> candidates(vm.id())
                        .map(candidates -> new ConfigurationSnapshot(new VirtualModel(vm.id(), vm.name(), vm.displayName(), vm.description(),
                                vm.enabled(), vm.routingPolicyId(), vm.createdAt(), vm.updatedAt()), vm.version(), policy, candidates)));
    }
    private Mono<List<RoutingCandidate>> candidates(String virtualModelId) {
        return bindings.findByVirtualModelId(virtualModelId).collectList().flatMap(rows -> {
            var providerIds = rows.stream().map(BindingEntity::providerId).distinct().toList();
            var modelIds = rows.stream().map(BindingEntity::providerModelId).distinct().toList();
            // Keep the reads sequential on the transaction's connection and bound each IN clause.
            return Flux.fromIterable(providerIds).buffer(LOOKUP_BATCH_SIZE).concatMap(providers::findAllById)
                    .collectMap(ProviderEntity::id).flatMap(providerRows ->
                            Flux.fromIterable(modelIds).buffer(LOOKUP_BATCH_SIZE).concatMap(models::findAllById)
                                    .collectMap(ProviderModelEntity::id)
                                    .map(modelRows -> rows.stream().map(b -> candidate(b,
                                            providerRows.get(b.providerId()), modelRows.get(b.providerModelId()))).toList()));
        });
    }
    private RoutingCandidate candidate(BindingEntity b, ProviderEntity p, ProviderModelEntity m) {
        if (p == null || m == null) throw new GatewayException(GatewayError.CONFIGURATION_CHANGED);
        ModelCapabilities modelCapabilities;
        ModelCapabilities override;
        String issue = null;
        try { modelCapabilities = CapabilityChecker.parse(m.capabilities()); override = CapabilityChecker.parse(b.capabilitiesOverride()); }
        catch (IllegalArgumentException ignored) { modelCapabilities = ModelCapabilities.empty(); override = ModelCapabilities.empty(); issue = "INVALID_CAPABILITIES"; }
        return new RoutingCandidate(new VirtualModelBinding(b.id(), b.virtualModelId(), b.providerId(), b.providerModelId(),
                b.enabled(), b.priority(), b.translationEnabled(), b.sourceProtocol(), b.targetProtocol(), override, b.createdAt(), b.updatedAt()),
                new Provider(p.id(), p.name(), p.baseUrl(), null, p.enabled(), p.protocol(), Duration.ofMillis(p.connectTimeoutMs()),
                        Duration.ofMillis(p.readTimeoutMs()), Duration.ofMillis(p.requestTimeoutMs()), p.maxRetries(), p.modelDiscoveryEnabled(),
                        p.modelDiscoveryUrl(), Duration.ofMillis(p.modelDiscoveryIntervalMs()), p.createdAt(), p.updatedAt()),
                new ProviderModel(m.id(), m.providerId(), m.modelName(), m.displayName(), m.status(), modelCapabilities, null,
                        m.firstSeenAt(), m.lastSeenAt(), m.createdAt(), m.updatedAt()), b.version(), p.version(), m.routingVersion(), issue);
    }
    public Mono<Boolean> current(String logicalModel, ConfigurationSnapshot snapshot, RoutingCandidate candidate) {
        return load(logicalModel).map(latest -> latest.virtualModel().id().equals(snapshot.virtualModel().id())
                && latest.candidates().stream().anyMatch(c -> c.binding().id().equals(candidate.binding().id())
                && c.binding().enabled() && c.provider().enabled() && c.model().status() == ProviderModelStatus.ACTIVE && c.configurationIssue() == null
                && latest.fingerprint(c).equals(snapshot.fingerprint(candidate))));
    }
}
