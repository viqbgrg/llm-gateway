package com.llmgateway.model;

/** Protocol-neutral events for one assistant message; payloads are fixed by the event variant. */
public sealed interface LlmStreamEvent permits LlmStreamEvent.MessageStart, LlmStreamEvent.BlockEvent,
        LlmStreamEvent.UsageUpdate, LlmStreamEvent.MessageEnd, LlmStreamEvent.Error {
    LlmStreamEventType type();

    /** Null is allowed only for an error before a message ID is known. */
    String messageId();

    sealed interface BlockEvent extends LlmStreamEvent permits ContentBlockStart, TextDelta, ThinkingDelta,
            ContentBlockEnd, ToolCallStart, ToolCallDelta, ToolCallEnd {
        int contentBlockIndex();
    }

    record MessageStart(String messageId) implements LlmStreamEvent {
        public MessageStart {
            validateMessageId(messageId);
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.MESSAGE_START;
        }
    }

    /** Opens a text or thinking block. Tool blocks use ToolCallStart instead. */
    record ContentBlockStart(String messageId, int contentBlockIndex, ContentBlockType contentType)
            implements BlockEvent {
        public ContentBlockStart {
            validateBlock(messageId, contentBlockIndex);
            if (contentType != ContentBlockType.TEXT && contentType != ContentBlockType.THINKING) {
                throw new IllegalArgumentException("stream content block must be text or thinking");
            }
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.CONTENT_BLOCK_START;
        }
    }

    record TextDelta(String messageId, int contentBlockIndex, String text) implements BlockEvent {
        public TextDelta {
            validateBlock(messageId, contentBlockIndex);
            validateDelta(text, "text delta", LlmInputLimits.MAX_PAYLOAD_CHARACTERS);
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.TEXT_DELTA;
        }
    }

    record ThinkingDelta(String messageId, int contentBlockIndex, String text) implements BlockEvent {
        public ThinkingDelta {
            validateBlock(messageId, contentBlockIndex);
            validateDelta(text, "thinking delta", LlmInputLimits.MAX_PAYLOAD_CHARACTERS);
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.THINKING_DELTA;
        }
    }

    record ContentBlockEnd(String messageId, int contentBlockIndex) implements BlockEvent {
        public ContentBlockEnd {
            validateBlock(messageId, contentBlockIndex);
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.CONTENT_BLOCK_END;
        }
    }

    record ToolCallStart(String messageId, int contentBlockIndex, String toolCallId, String toolName)
            implements BlockEvent {
        public ToolCallStart {
            validateToolCall(messageId, contentBlockIndex, toolCallId);
            IrValidation.requiredText(toolName, "tool name", LlmInputLimits.MAX_TOOL_NAME_LENGTH);
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.TOOL_CALL_START;
        }
    }

    /** An exact string fragment, not a JsonNode: it may end inside a JSON token or escape sequence. */
    record ToolCallDelta(String messageId, int contentBlockIndex, String toolCallId, String argumentsDelta)
            implements BlockEvent {
        public ToolCallDelta {
            validateToolCall(messageId, contentBlockIndex, toolCallId);
            validateDelta(argumentsDelta, "tool argument delta", LlmInputLimits.MAX_JSON_CHARACTERS);
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.TOOL_CALL_DELTA;
        }
    }

    /** Closes a call only after its concatenated argument fragments form a complete JSON object. */
    record ToolCallEnd(String messageId, int contentBlockIndex, String toolCallId) implements BlockEvent {
        public ToolCallEnd {
            validateToolCall(messageId, contentBlockIndex, toolCallId);
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.TOOL_CALL_END;
        }
    }

    /** A snapshot of reported counters, never a token delta; unknown counters remain null. */
    record UsageUpdate(String messageId, Usage usage) implements LlmStreamEvent {
        public UsageUpdate {
            validateMessageId(messageId);
            if (usage == null) {
                throw new IllegalArgumentException("stream usage is required");
            }
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.USAGE;
        }
    }

    record MessageEnd(String messageId, FinishReason finishReason) implements LlmStreamEvent {
        public MessageEnd {
            validateMessageId(messageId);
            if (finishReason == null) {
                throw new IllegalArgumentException("stream finish reason is required");
            }
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.MESSAGE_END;
        }
    }

    /** A terminal failure carrying only a closed set of safe errors, without an upstream body or cause. */
    record Error(String messageId, LlmStreamError error) implements LlmStreamEvent {
        public Error {
            if (messageId != null) {
                validateMessageId(messageId);
            }
            if (error == null) {
                throw new IllegalArgumentException("stream error is required");
            }
        }

        @Override
        public LlmStreamEventType type() {
            return LlmStreamEventType.ERROR;
        }
    }

    private static void validateMessageId(String messageId) {
        IrValidation.requiredText(messageId, "stream message ID", LlmInputLimits.MAX_IDENTIFIER_LENGTH);
    }

    private static void validateBlock(String messageId, int contentBlockIndex) {
        validateMessageId(messageId);
        if (contentBlockIndex < 0 || contentBlockIndex >= LlmInputLimits.MAX_CONTENT_BLOCKS) {
            throw new IllegalArgumentException("stream content block index is out of range");
        }
    }

    private static void validateToolCall(String messageId, int contentBlockIndex, String toolCallId) {
        validateBlock(messageId, contentBlockIndex);
        IrValidation.requiredText(toolCallId, "tool call ID", LlmInputLimits.MAX_IDENTIFIER_LENGTH);
    }

    private static void validateDelta(String delta, String field, int maxLength) {
        if (delta == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (delta.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
    }
}
