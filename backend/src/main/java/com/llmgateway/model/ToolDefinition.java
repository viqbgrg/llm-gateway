package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(String name, String description, JsonNode inputSchema) {
    public ToolDefinition {
        IrValidation.requiredText(name, "tool name", LlmInputLimits.MAX_TOOL_NAME_LENGTH);
        inputSchema = IrValidation.copyJsonObject(inputSchema, "tool input schema");
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
