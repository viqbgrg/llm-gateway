package com.llmgateway.routing;

import com.llmgateway.model.LlmRequest;
import com.llmgateway.protocol.RequestContext;
import reactor.core.publisher.Mono;

public interface ModelRouter {
    Mono<RoutingDecision> route(LlmRequest request, RequestContext context);
}
