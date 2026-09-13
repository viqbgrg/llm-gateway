package com.llmgateway.discovery;

import com.llmgateway.admin.*;
import com.llmgateway.config.DiscoveryProperties;
import com.llmgateway.inference.*;
import com.llmgateway.model.ProviderModelStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DiscoveryReconciler {
    private final ProviderRepository providers;
    private final ProviderModelRepository models;
    private final DatabaseClient database;
    private final TransactionalOperator transaction;
    private final DiscoveryLeaseService leases;
    private final DiscoveryProperties properties;
    private final Clock clock;
    public DiscoveryReconciler(ProviderRepository providers, ProviderModelRepository models, DatabaseClient database,
                               ReactiveTransactionManager manager, DiscoveryLeaseService leases, DiscoveryProperties properties, Clock clock) {
        this.providers = providers; this.models = models; this.database = database; this.transaction = TransactionalOperator.create(manager);
        this.leases = leases; this.properties = properties; this.clock = clock;
    }
    public Mono<Long> allocate(String providerId) {
        return providers.findByIdForUpdate(providerId).switchIfEmpty(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)))
                .then(database.sql("INSERT IGNORE INTO provider_discovery_state(provider_id) VALUES (:id)").bind("id", providerId).then())
                .then(database.sql("UPDATE provider_discovery_state SET allocated_generation = allocated_generation + 1 WHERE provider_id = :id").bind("id", providerId).then())
                .then(database.sql("SELECT allocated_generation FROM provider_discovery_state WHERE provider_id = :id").bind("id", providerId)
                        .map((row, meta) -> row.get("allocated_generation", Long.class)).one()).as(transaction::transactional);
    }
    public Mono<Result> apply(ProviderEntity fetchedProvider, DiscoveryLeaseService.Lease lease, long generation, List<DiscoveredModel> catalog, boolean reconcile) {
        return providers.findByIdForUpdate(fetchedProvider.id()).switchIfEmpty(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)))
                .flatMap(current -> !Objects.equals(current.version(), fetchedProvider.version()) || reconcile && (!current.enabled() || !current.modelDiscoveryEnabled())
                        ? Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)) : Mono.just(current))
                .then(database.sql("SELECT allocated_generation FROM provider_discovery_state WHERE provider_id = :id").bind("id", fetchedProvider.id())
                        .map((row, meta) -> row.get("allocated_generation", Long.class)).one())
                .filter(current -> current == generation).switchIfEmpty(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)))
                .then(leases.owned(lease)).filter(Boolean::booleanValue).switchIfEmpty(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)))
                .then(models.findByProviderId(fetchedProvider.id()).collectList())
                .flatMap(existing -> merge(fetchedProvider.id(), generation, catalog, existing, reconcile))
                .flatMap(result -> leases.owned(lease).filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)))
                        .then(database.sql("UPDATE provider_discovery_state SET applied_generation = :generation WHERE provider_id = :id AND allocated_generation = :generation")
                        .bind("generation", generation).bind("id", fetchedProvider.id()).fetch().rowsUpdated()
                        .filter(count -> count == 1).switchIfEmpty(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED))).thenReturn(result)))
                .as(transaction::transactional);
    }
    private Mono<Result> merge(String provider, long generation, List<DiscoveredModel> catalog, List<ProviderModelEntity> existing, boolean reconcile) {
        Map<String, ProviderModelEntity> byName = new HashMap<>(); existing.forEach(model -> byName.put(model.modelName(), model));
        Set<String> seen = new HashSet<>(); List<ProviderModelEntity> changes = new ArrayList<>();
        int created = 0, updated = 0, missing = 0, removed = 0, reappeared = 0;
        Instant now = clock.instant();
        for (DiscoveredModel discovered : catalog) {
            if (!seen.add(discovered.modelName())) continue;
            ProviderModelEntity old = byName.get(discovered.modelName());
            if (old == null) {
                created++;
                changes.add(new ProviderModelEntity(UUID.randomUUID().toString(), provider, discovered.modelName(), discovered.displayName(),
                        ProviderModelStatus.NEW, "[]", discovered.rawMetadata().toString(), now, now, now, now, null, 0, null, generation, null, null, 0));
            } else {
                updated++;
                boolean restore = "DISCOVERY".equals(old.removalSource()) && old.status() == ProviderModelStatus.REMOVED && old.preRemovalStatus() != null;
                if (restore) reappeared++;
                changes.add(new ProviderModelEntity(old.id(), provider, old.modelName(), old.displayName(), restore ? old.preRemovalStatus() : old.status(),
                        old.capabilities(), discovered.rawMetadata().toString(), old.firstSeenAt(), now, old.createdAt(), now, old.version(), 0, null, generation,
                        restore ? null : old.removalSource(), restore ? null : old.preRemovalStatus(), old.routingVersion() + (restore ? 1 : 0)));
            }
        }
        if (reconcile) for (ProviderModelEntity old : existing) {
            if (seen.contains(old.modelName())) continue;
            missing++;
            int count = Math.min(1_000_000, old.missingCount() + 1);
            boolean remove = count >= properties.missingConfirmations() && (old.status() == ProviderModelStatus.ACTIVE || old.status() == ProviderModelStatus.NEW);
            if (remove) removed++;
            changes.add(new ProviderModelEntity(old.id(), provider, old.modelName(), old.displayName(), remove ? ProviderModelStatus.REMOVED : old.status(),
                    old.capabilities(), old.rawMetadata(), old.firstSeenAt(), old.lastSeenAt(), old.createdAt(), now, old.version(), count,
                    old.firstMissingAt() == null ? now : old.firstMissingAt(), generation, remove ? "DISCOVERY" : old.removalSource(),
                    remove ? old.status() : old.preRemovalStatus(), old.routingVersion() + (remove ? 1 : 0)));
        }
        Result result = new Result(created, updated, seen.size(), missing, removed, reappeared, generation);
        return Flux.fromIterable(changes).concatMap(models::save).then(Mono.just(result));
    }
    public record Result(int created, int updated, int total, int missing, int removed, int reappeared, long generation) {}
}
