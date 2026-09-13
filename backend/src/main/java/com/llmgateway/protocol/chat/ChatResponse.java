package com.llmgateway.protocol.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(String id, String object, long created, String model, List<Choice> choices, TokenUsage usage) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Choice(int index, ChatRequest.ChatMessage message, String finishReason) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenUsage(Long promptTokens, Long completionTokens, Long totalTokens) {}
}
