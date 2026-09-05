package com.llmgateway.routing;

import com.llmgateway.model.VirtualModelBinding;
import java.util.List;

public record RoutingDecision(VirtualModelBinding selectedBinding, List<VirtualModelBinding> candidateBindings,
                              String routingReason) {
    public RoutingDecision {
        candidateBindings = candidateBindings == null ? List.of() : List.copyOf(candidateBindings);
    }
}
