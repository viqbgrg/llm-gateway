package com.llmgateway.routing;

import com.llmgateway.admin.*;
import com.llmgateway.inference.*;
import java.util.Comparator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class VirtualModelResolver {
    private final VirtualModelRepository models;
    private final ModelRuleRepository rules;
    public VirtualModelResolver(VirtualModelRepository models, ModelRuleRepository rules) { this.models = models; this.rules = rules; }
    public Mono<VirtualModelEntity> resolve(String name) {
        if (name == null || name.isBlank() || name.length() > 255) return Mono.error(GatewayException.invalid());
        return models.findByName(name).switchIfEmpty(Mono.defer(() -> rules.findByEnabledTrue()
                .filter(rule -> rule.virtualModelId() != null && WildcardMatcher.matches(rule.pattern(), name))
                .sort(Comparator.comparingInt(ModelRuleEntity::priority)
                        .thenComparing(Comparator.comparingInt((ModelRuleEntity r) -> WildcardMatcher.specificity(r.pattern())).reversed())
                        .thenComparing(ModelRuleEntity::createdAt).thenComparing(ModelRuleEntity::id))
                .next().flatMap(rule -> models.findById(rule.virtualModelId()))))
                .switchIfEmpty(Mono.error(new GatewayException(GatewayError.MODEL_NOT_FOUND)))
                .flatMap(model -> model.enabled() ? Mono.just(model) : Mono.error(new GatewayException(GatewayError.MODEL_DISABLED)));
    }
}
