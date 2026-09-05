package com.llmgateway.routing;

import com.llmgateway.model.LlmRequest;
import com.llmgateway.model.VirtualModel;
import reactor.core.publisher.Mono;

public interface ModelRouter {
    Mono<RoutingDecision> route(LlmRequest request, VirtualModel virtualModel);
}
