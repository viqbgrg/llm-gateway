package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments) implements ContentBlock {
    public ToolCall {
        IrValidation.requiredText(id, "tool call ID", LlmInputLimits.MAX_IDENTIFIER_LENGTH);
        IrValidation.requiredText(name, "tool name", LlmInputLimits.MAX_TOOL_NAME_LENGTH);
        arguments = IrValidation.copyJsonObject(arguments, "tool arguments");
    }

    @Override
    public ContentBlockType type() {
        return ContentBlockType.TOOL_CALL;
    }

    @Override
    public JsonNode arguments() {
        return arguments.deepCopy();
    }
}
