package com.llmgateway.protocol.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResponsesResponse(String id, String object, long createdAt, String status, String model,
                                List<JsonNode> output, JsonNode error, JsonNode incompleteDetails, TokenUsage usage,
                                boolean store) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) {}
}
