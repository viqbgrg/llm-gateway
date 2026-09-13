package com.llmgateway.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnthropicResponse(String id, String type, String role, String model, List<JsonNode> content,
                                String stopReason, String stopSequence, TokenUsage usage) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenUsage(Long inputTokens, Long outputTokens) {}
}
