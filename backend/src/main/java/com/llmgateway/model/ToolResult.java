package com.llmgateway.model;

import java.util.List;
import java.util.Objects;

public record ToolResult(String toolCallId, List<ContentBlock> content, boolean isError) implements ContentBlock {
    public ToolResult {
        IrValidation.requiredText(toolCallId, "tool result call ID", LlmInputLimits.MAX_IDENTIFIER_LENGTH);
        if (content != null && content.size() > LlmInputLimits.MAX_BLOCKS_PER_MESSAGE) {
            throw new IllegalArgumentException("tool result content exceeds maximum count");
        }
        content = List.copyOf(Objects.requireNonNull(content, "tool result content is required"));
        if (content.stream().anyMatch(block -> block instanceof ToolCall
                || block instanceof ToolResult || block instanceof ContentBlock.Thinking)) {
            throw new IllegalArgumentException("tool results may contain only text and media blocks");
        }
    }

    @Override
    public ContentBlockType type() {
        return ContentBlockType.TOOL_RESULT;
    }
}
