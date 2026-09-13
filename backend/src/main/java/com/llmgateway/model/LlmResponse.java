package com.llmgateway.model;

import java.util.HashSet;
import java.util.List;

public record LlmResponse(
        String id,
        String model,
        List<ContentBlock> content,
        FinishReason finishReason,
        Usage usage) {
    public LlmResponse {
        IrValidation.requiredText(id, "response ID", LlmInputLimits.MAX_IDENTIFIER_LENGTH);
        IrValidation.requiredText(model, "response model", LlmInputLimits.MAX_MODEL_NAME_LENGTH);
        if (finishReason == null) {
            throw new IllegalArgumentException("finish reason is required");
        }
        content = IrValidation.requiredList(content, "response content", LlmInputLimits.MAX_CONTENT_BLOCKS);
        Message.validateContent(MessageRole.ASSISTANT, content);
        var callIds = new HashSet<String>();
        for (ContentBlock block : content) {
            if (block instanceof ToolCall call && !callIds.add(call.id())) {
                throw new IllegalArgumentException("response tool call IDs must be unique");
            }
        }
        if (finishReason == FinishReason.TOOL_CALLS && callIds.isEmpty()) {
            throw new IllegalArgumentException("tool call finish reason requires tool calls");
        }
    }
}
