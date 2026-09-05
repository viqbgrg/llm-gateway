package com.llmgateway.model;

import java.util.List;

public record Message(MessageRole role, List<ContentBlock> content) {
    public Message {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
