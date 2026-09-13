package com.llmgateway.admin;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import com.llmgateway.routing.WildcardMatcher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class RoutingConfigurationService {
    private final ModelRuleRepository rules;
    private final RoutingPolicyRepository policies;
    private final VirtualModelRepository models;
    private final DatabaseClient database;
    private final TransactionalOperator transactions;
    private final Clock clock;
    private final com.llmgateway.infrastructure.RuntimeStateCleanup runtime;
    public RoutingConfigurationService(ModelRuleRepository rules, RoutingPolicyRepository policies, VirtualModelRepository models,
                                       DatabaseClient database, ReactiveTransactionManager manager, Clock clock,
                                       com.llmgateway.infrastructure.RuntimeStateCleanup runtime) {
        this.rules = rules; this.policies = policies; this.models = models; this.database = database;
        this.transactions = TransactionalOperator.create(manager); this.clock = clock;
        this.runtime = runtime;
    }
    public Flux<ModelRuleEntity> rules() { return rules.findAll(); }
    public Mono<ModelRuleEntity> rule(String id) { return AdminValidation.required(rules.findById(id), "Model rule"); }
    public Flux<RoutingPolicyEntity> policies() { return policies.findAll(); }
    public Mono<RoutingPolicyEntity> policy(String id) { return AdminValidation.required(policies.findById(id), "Routing policy"); }
    public Mono<ModelRuleEntity> saveRule(String id, RuleRequest request) {
        WildcardMatcher.validate(request.pattern());
        if (request.priority() < 0 || request.enabled() && request.virtualModelId() == null) throw new IllegalArgumentException("Enabled rules require a target and nonnegative priority");
        Mono<ModelRuleEntity> current = id == null
                ? Mono.just(new ModelRuleEntity(UUID.randomUUID().toString(), request.pattern(), request.priority(), request.enabled(), request.virtualModelId(), clock.instant(), clock.instant(), null))
                : rule(id);
        return database.sql("SELECT id FROM routing_configuration_lock WHERE id = 1 FOR UPDATE").fetch().one()
                .then(request.virtualModelId() == null ? Mono.empty() : AdminValidation.required(models.findById(request.virtualModelId()), "Virtual model"))
                .thenMany(rules.findByPatternAndPriorityAndEnabledTrue(request.pattern(), request.priority()))
                .filter(r -> request.enabled() && !r.id().equals(id) && !Objects.equals(r.virtualModelId(), request.virtualModelId()))
                .hasElements().flatMap(conflict -> conflict ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Enabled rule has an ambiguous target")) : current)
                .flatMap(existing -> {
                    requireVersion(id, existing.version(), request.version());
                    return rules.save(new ModelRuleEntity(existing.id(), request.pattern(), request.priority(), request.enabled(), request.virtualModelId(),
                            existing.createdAt(), clock.instant(), existing.version()));
                }).as(transactions::transactional);
    }
    public Mono<RoutingPolicyEntity> savePolicy(String id, RoutingPolicyEntity.PolicyRequest request) {
        var entity = RoutingPolicyEntity.from(id == null ? UUID.randomUUID().toString() : id, request);
        entity.domain();
        if (id == null && request.version() != null) throw new IllegalArgumentException("New policy must not have a version");
        return (id == null ? Mono.just(entity) : policy(id).map(existing -> {
            requireVersion(id, existing.version(), request.version()); return entity;
        })).flatMap(policies::save).flatMap(saved -> models.findByRoutingPolicyId(saved.id())
                .concatMap(model -> runtime.virtualModel(model.id())).then(Mono.just(saved)));
    }
    public Mono<Void> deleteRule(String id) { return rule(id).flatMap(rules::delete); }
    public Mono<Void> deletePolicy(String id) { return policy(id).flatMap(policies::delete); }
    private static void requireVersion(String id, Long current, Long supplied) {
        if (id != null && !Objects.equals(current, supplied)) throw new OptimisticLockingFailureException("Configuration version changed");
    }
    public record RuleRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String pattern,
                              int priority, boolean enabled, @jakarta.validation.constraints.Size(max = 36) String virtualModelId, Long version) {}
}
