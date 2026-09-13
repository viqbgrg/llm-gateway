package com.llmgateway.routing;

import java.util.List;

public record RoutingDecision(ConfigurationSnapshot configuration, List<RoutingCandidate> candidateBindings,
                              List<DefaultModelRouter.CandidateView> evaluation, String routingReason) {
    public RoutingDecision {
        candidateBindings = candidateBindings == null ? List.of() : List.copyOf(candidateBindings);
        evaluation = List.copyOf(evaluation);
    }
}
