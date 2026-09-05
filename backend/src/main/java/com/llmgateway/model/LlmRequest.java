package com.llmgateway.model;

import java.util.List;

public record LlmRequest(
        String model,
        List<Message> messages,
        List<ToolDefinition> tools,
        ReasoningConfig reasoning,
        GenerationConfig generation,
        ResponseFormat responseFormat,
        boolean stream) {
    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
