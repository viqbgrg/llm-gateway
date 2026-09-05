package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ContentBlock(ContentBlockType type, String text, JsonNode data) {
    public static ContentBlock text(String value) {
        return new ContentBlock(ContentBlockType.TEXT, value, null);
    }
}
