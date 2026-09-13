package com.llmgateway.routing;

import com.llmgateway.model.*;
import java.util.List;

public record ConfigurationSnapshot(VirtualModel virtualModel, long virtualModelVersion, RoutingPolicy policy,
                                    List<RoutingCandidate> candidates) {
    public ConfigurationSnapshot { candidates = List.copyOf(candidates); }
    public String fingerprint(RoutingCandidate candidate) {
        return virtualModelVersion + ":" + policy.version() + ":" + candidate.fingerprint();
    }
}
