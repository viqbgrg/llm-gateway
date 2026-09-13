package com.llmgateway.routing;

import com.llmgateway.model.*;

/** Public configuration only; credentials are resolved immediately before transport use. */
public record RoutingCandidate(VirtualModelBinding binding, Provider provider, ProviderModel model,
                               long bindingVersion, long providerVersion, long modelVersion, String configurationIssue) {
    public RoutingCandidate(VirtualModelBinding binding, Provider provider, ProviderModel model,
                            long bindingVersion, long providerVersion, long modelVersion) {
        this(binding, provider, model, bindingVersion, providerVersion, modelVersion, null);
    }
    public String fingerprint() { return bindingVersion + ":" + providerVersion + ":" + modelVersion; }
    public String destination() { return provider.id() + ":" + model.id() + ":" + binding.sourceProtocol() + ":" + binding.targetProtocol(); }
}
