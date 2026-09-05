package com.llmgateway.model;

import java.util.List;

public record LlmResponse(
        String id,
        String model,
        List<ContentBlock> content,
        String finishReason,
        Usage usage) {
    public LlmResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
