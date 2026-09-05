package com.llmgateway.provider;

import com.llmgateway.model.LlmRequest;
import com.llmgateway.model.LlmResponse;
import com.llmgateway.model.LlmStreamEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProviderAdapter {
    Mono<LlmResponse> execute(LlmRequest request, ProviderContext context);
    Flux<LlmStreamEvent> stream(LlmRequest request, ProviderContext context);
}
