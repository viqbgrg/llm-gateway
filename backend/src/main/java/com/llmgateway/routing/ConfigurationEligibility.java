package com.llmgateway.routing;

import com.llmgateway.model.Protocol;
import com.llmgateway.model.ProviderModelStatus;
import com.llmgateway.protocol.TranslationValidator;

/** Shared, side-effect-free configuration checks for execution, preview and administration. */
public final class ConfigurationEligibility {
    private ConfigurationEligibility() {}

    public static String reason(RoutingCandidate candidate, Protocol source, TranslationValidator translation) {
        if (candidate.configurationIssue() != null) return candidate.configurationIssue();
        if (!candidate.model().providerId().equals(candidate.provider().id())) return "MODEL_OWNERSHIP";
        if (candidate.binding().sourceProtocol() != source || !translation.available(source, candidate.binding().targetProtocol(),
                candidate.provider().protocol(), candidate.binding().translationEnabled())) return "PROTOCOL_UNSUPPORTED";
        if (!candidate.binding().enabled()) return "BINDING_DISABLED";
        if (!candidate.provider().enabled()) return "PROVIDER_DISABLED";
        if (candidate.model().status() != ProviderModelStatus.ACTIVE) return "MODEL_" + candidate.model().status().name();
        return "ELIGIBLE";
    }
}
