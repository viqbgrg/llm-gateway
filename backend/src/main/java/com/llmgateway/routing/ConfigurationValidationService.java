package com.llmgateway.routing;

import com.llmgateway.admin.*;
import com.llmgateway.protocol.TranslationValidator;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Returns identifiers and repair instructions only, never persisted payloads or credentials. */
@Service
public class ConfigurationValidationService {
    private final VirtualModelRepository virtualModels;
    private final ProviderModelRepository models;
    private final ModelRuleRepository rules;
    private final ConfigurationStore configurations;
    private final TranslationValidator translation;

    public ConfigurationValidationService(VirtualModelRepository virtualModels, ProviderModelRepository models, ModelRuleRepository rules,
                                           ConfigurationStore configurations, TranslationValidator translation) {
        this.virtualModels = virtualModels; this.models = models; this.rules = rules;
        this.configurations = configurations; this.translation = translation;
    }

    public Mono<List<Issue>> validate() {
        Flux<Issue> modelIssues = models.findAll().handle((model, sink) -> {
            try { CapabilityChecker.parse(model.capabilities()); }
            catch (IllegalArgumentException ignored) {
                sink.next(new Issue("provider-models", model.id(), "INVALID_CAPABILITIES", "Save an array of supported capability names in the model editor."));
            }
        });
        Flux<Issue> bindingIssues = virtualModels.findAll().concatMap(vm -> configurations.inspect(vm.id()))
                .flatMapIterable(ConfigurationSnapshot::candidates).handle((candidate, sink) -> {
                    String reason = ConfigurationEligibility.reason(candidate, candidate.binding().sourceProtocol(), translation);
                    String repair = switch (reason) {
                        case "INVALID_CAPABILITIES" -> "Correct the model capabilities or the binding override using supported capability names.";
                        case "MODEL_OWNERSHIP" -> "Select a model that belongs to the binding provider.";
                        case "PROTOCOL_UNSUPPORTED" -> "Select an implemented protocol chain and explicitly enable cross-protocol translation.";
                        default -> null;
                    };
                    if (repair != null) sink.next(new Issue("bindings", candidate.binding().id(), reason, repair));
                });
        Flux<Issue> ruleIssues = rules.findAll().filter(rule -> rule.virtualModelId() == null)
                .map(rule -> new Issue("model-rules", rule.id(), "MISSING_RULE_TARGET", "Choose a virtual model before enabling this legacy rule."));
        return Flux.concat(modelIssues, bindingIssues, ruleIssues).collectList();
    }

    public record Issue(String resource, String id, String code, String repair) {}
}
