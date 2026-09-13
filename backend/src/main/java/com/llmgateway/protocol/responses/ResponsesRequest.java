package com.llmgateway.protocol.responses;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.llmgateway.inference.GatewayException;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResponsesRequest(String model, JsonNode input, String instructions, List<JsonNode> tools, JsonNode toolChoice,
                               Double temperature, Double topP, Integer maxOutputTokens, JsonNode text,
                               Boolean stream, Boolean store) {
    @JsonAnySetter public void unsupported(String name, JsonNode value) { throw GatewayException.invalid(); }
}
