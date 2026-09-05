package com.llmgateway.model;

public enum LlmStreamEventType {
    MESSAGE_START, TEXT_DELTA, THINKING_DELTA, TOOL_CALL_START, TOOL_CALL_DELTA,
    TOOL_CALL_END, USAGE, MESSAGE_END, ERROR
}
