package com.llmgateway.discovery;

import com.llmgateway.model.Provider;
import reactor.core.publisher.Mono;
import java.util.List;

public interface ProviderModelDiscovery {
    Mono<List<DiscoveredModel>> discover(Provider provider);
}
