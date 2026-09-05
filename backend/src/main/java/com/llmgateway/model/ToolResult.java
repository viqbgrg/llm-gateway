package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolResult(String toolCallId, JsonNode content, boolean isError) {}
