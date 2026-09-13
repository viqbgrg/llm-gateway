package com.llmgateway.protocol.chat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.llmgateway.inference.GatewayException;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(String model, List<ChatMessage> messages, List<ChatTool> tools, JsonNode toolChoice,
                          Double temperature, Double topP, Integer maxTokens, Integer seed, JsonNode stop,
                          JsonNode responseFormat, Boolean stream, Integer n, StreamOptions streamOptions) {
    @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatMessage(String role, JsonNode content, List<ChatCall> toolCalls, String toolCallId) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChatTool(String type, FunctionDefinition function) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionDefinition(String name, String description, JsonNode parameters) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
    public record ChatCall(String id, String type, FunctionCall function) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
    public record FunctionCall(String name, String arguments) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StreamOptions(Boolean includeUsage) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
}
