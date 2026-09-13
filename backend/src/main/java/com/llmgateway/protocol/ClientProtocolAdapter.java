package com.llmgateway.protocol;

import com.llmgateway.model.LlmRequest;
import com.llmgateway.model.LlmResponse;
import com.llmgateway.model.LlmStreamEvent;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;

/** HTTP reads the DTO; adapters perform only protocol translation. */
public interface ClientProtocolAdapter<I, O> {
    LlmRequest parse(I input, RequestContext context);
    O encode(LlmResponse response);
    Flux<ServerSentEvent<String>> encodeStream(Flux<LlmStreamEvent> events, String logicalModel);
}
