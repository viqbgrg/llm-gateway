package com.llmgateway.protocol;

import com.llmgateway.model.LlmRequest;
import com.llmgateway.model.LlmResponse;
import com.llmgateway.model.LlmStreamEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClientProtocolAdapter {
    LlmRequest parse(RequestContext context);
    Mono<Object> encode(LlmResponse response);
    Flux<Object> encodeStream(Flux<LlmStreamEvent> events);
}
