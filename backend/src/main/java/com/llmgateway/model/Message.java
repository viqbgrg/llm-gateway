package com.llmgateway.model;

import java.util.List;

public record Message(MessageRole role, List<ContentBlock> content) {
    public Message {
        if (role == null) {
            throw new IllegalArgumentException("message role is required");
        }
        content = IrValidation.requiredList(content, "message content", LlmInputLimits.MAX_BLOCKS_PER_MESSAGE);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("message content must not be empty");
        }
        validateContent(role, content);
    }

    static void validateContent(MessageRole role, List<ContentBlock> content) {
        for (ContentBlock block : content) {
            if (role == MessageRole.TOOL && !(block instanceof ToolResult)) {
                throw new IllegalArgumentException("tool messages may contain only tool results");
            }
            if (block instanceof ToolResult && role != MessageRole.TOOL) {
                throw new IllegalArgumentException("tool results require a tool message");
            }
            if ((block instanceof ToolCall || block instanceof ContentBlock.Thinking) && role != MessageRole.ASSISTANT) {
                throw new IllegalArgumentException("tool calls and thinking require an assistant message");
            }
        }
    }
}
