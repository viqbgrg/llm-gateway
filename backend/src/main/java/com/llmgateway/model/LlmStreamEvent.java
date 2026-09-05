package com.llmgateway.model;

public record LlmStreamEvent(LlmStreamEventType type, String text, Usage usage, String error) {}
