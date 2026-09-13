package com.llmgateway.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.llmgateway.inference.GatewayException;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnthropicRequest(String model, List<InputMessage> messages, JsonNode system, Integer maxTokens,
                               Double temperature, Double topP, List<String> stopSequences, List<InputTool> tools,
                               JsonNode toolChoice, Boolean stream) {
    @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    public record InputMessage(String role, JsonNode content) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InputTool(String name, String description, JsonNode inputSchema) {
        @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
    }
}
