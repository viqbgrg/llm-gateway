package com.llmgateway.model;

import java.util.HashSet;
import java.util.List;

final class LlmRequestValidation {
    private LlmRequestValidation() {}

    static void validateToolChoice(List<ToolDefinition> tools, ToolChoice choice) {
        var names = new HashSet<String>();
        for (ToolDefinition tool : tools) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("tool names must be unique");
            }
        }
        if (choice != ToolChoice.Mode.NONE && tools.isEmpty()) {
            throw new IllegalArgumentException("tool choice requires tools");
        }
        if (choice instanceof ToolChoice.Named named && !names.contains(named.name())) {
            throw new IllegalArgumentException("named tool choice must reference a declared tool");
        }
    }

    static void validateToolHistory(List<Message> messages) {
        var seen = new HashSet<String>();
        var pending = new HashSet<String>();
        for (Message message : messages) {
            if (!pending.isEmpty() && message.role() != MessageRole.TOOL) {
                throw new IllegalArgumentException("tool calls require results before the next non-tool message");
            }
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolCall call) {
                    if (!seen.add(call.id())) {
                        throw new IllegalArgumentException("tool call IDs must be unique within a request");
                    }
                    pending.add(call.id());
                } else if (block instanceof ToolResult result && !pending.remove(result.toolCallId())) {
                    throw new IllegalArgumentException("tool result must reference an unresolved preceding call");
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException("tool calls require results before requesting another generation");
        }
    }

    static void validateSize(String model, List<Message> messages, List<ToolDefinition> tools,
                             ToolChoice toolChoice, GenerationConfig generation, ResponseFormat responseFormat) {
        var budget = new PayloadBudget();
        budget.add(model);
        for (Message message : messages) {
            for (ContentBlock block : message.content()) {
                budget.add(block);
            }
        }
        for (ToolDefinition tool : tools) {
            budget.add(tool.name());
            budget.add(tool.description());
            budget.add(IrValidation.jsonLength(tool.inputSchema()));
        }
        if (toolChoice instanceof ToolChoice.Named named) {
            budget.add(named.name());
        }
        if (generation != null) {
            generation.stopSequences().forEach(budget::add);
        }
        if (responseFormat instanceof ResponseFormat.JsonSchema schema) {
            budget.add(schema.name());
            budget.add(schema.description());
            budget.add(IrValidation.jsonLength(schema.schema()));
        }
    }

    private static final class PayloadBudget {
        private long characters;
        private int blocks;

        private void add(String value) {
            if (value != null) {
                add(value.length());
            }
        }

        private void add(int count) {
            characters += count;
            if (characters > LlmInputLimits.MAX_PAYLOAD_CHARACTERS) {
                throw new IllegalArgumentException("request payload exceeds maximum character count");
            }
        }

        private void add(ContentBlock block) {
            if (++blocks > LlmInputLimits.MAX_CONTENT_BLOCKS) {
                throw new IllegalArgumentException("request content exceeds maximum block count");
            }
            switch (block) {
                case ContentBlock.Text text -> add(text.text());
                case ContentBlock.Thinking thinking -> add(thinking.text());
                case ContentBlock.Image image -> add(image.source());
                case ContentBlock.Audio audio -> add(audio.source());
                case ContentBlock.Video video -> add(video.source());
                case ContentBlock.Document document -> add(document.source());
                case ToolCall call -> {
                    add(call.id());
                    add(call.name());
                    add(IrValidation.jsonLength(call.arguments()));
                }
                case ToolResult result -> {
                    add(result.toolCallId());
                    result.content().forEach(this::add);
                }
            }
        }

        private void add(MediaSource source) {
            add(source.mimeType());
            switch (source) {
                case MediaSource.Url url -> add(url.url().toString());
                case MediaSource.InlineData inline -> add(inline.data());
            }
        }
    }
}
