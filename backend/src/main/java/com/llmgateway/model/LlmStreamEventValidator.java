package com.llmgateway.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates one ordered stream, with bounded buffering only for unfinished tool arguments.
 * Create one instance per subscription; this class is not thread-safe. Call accept for each event,
 * then complete on normal source completion. Cancellation/transport failure is not normal completion.
 * A contract violation invalidates the instance and discards buffered arguments.
 */
public final class LlmStreamEventValidator {
    private static final JsonFactory ARGUMENT_JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(LlmInputLimits.MAX_JSON_DEPTH)
                    .maxStringLength(LlmInputLimits.MAX_JSON_CHARACTERS)
                    .maxNameLength(LlmInputLimits.MAX_JSON_CHARACTERS)
                    .maxNumberLength(LlmInputLimits.MAX_JSON_CHARACTERS)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Map<Integer, OpenBlock> openBlocks = new HashMap<>();
    private final Set<String> toolCallIds = new HashSet<>();
    private State state = State.NEW;
    private String messageId;
    private int nextContentBlockIndex;
    private int bufferedArgumentCharacters;

    public void accept(LlmStreamEvent event) {
        try {
            validateEvent(event);
        } catch (IllegalArgumentException exception) {
            state = State.INVALID;
            discardBuffers();
            throw exception;
        }
    }

    /** Detects an empty or truncated source instead of inventing a successful message end. */
    public void complete() {
        if (state != State.TERMINATED) {
            state = State.INVALID;
            discardBuffers();
            throw invalid("stream ended without a terminal event");
        }
    }

    private void validateEvent(LlmStreamEvent event) {
        if (event == null) {
            throw invalid("stream event is required");
        }
        if (state == State.TERMINATED || state == State.INVALID) {
            throw invalid("stream cannot accept events after termination or a contract violation");
        }
        if (state == State.NEW && !(event instanceof LlmStreamEvent.MessageStart)
                && !(event instanceof LlmStreamEvent.Error)) {
            throw invalid("stream must begin with a message start or an error");
        }
        if (state == State.STARTED && !messageId.equals(event.messageId())) {
            throw invalid("stream event must reference the active message");
        }

        switch (event) {
            case LlmStreamEvent.MessageStart start -> {
                if (state != State.NEW) {
                    throw invalid("stream message has already started");
                }
                messageId = start.messageId();
                state = State.STARTED;
            }
            case LlmStreamEvent.ContentBlockStart start -> openBlock(start.contentBlockIndex(),
                    new OpenBlock(start.contentType(), null, null));
            case LlmStreamEvent.TextDelta delta -> requireBlock(delta.contentBlockIndex(), ContentBlockType.TEXT);
            case LlmStreamEvent.ThinkingDelta delta -> requireBlock(delta.contentBlockIndex(), ContentBlockType.THINKING);
            case LlmStreamEvent.ContentBlockEnd end -> {
                OpenBlock block = openBlocks.get(end.contentBlockIndex());
                if (block == null || block.type() == ContentBlockType.TOOL_CALL) {
                    throw invalid("content block end requires an open text or thinking block");
                }
                openBlocks.remove(end.contentBlockIndex());
            }
            case LlmStreamEvent.ToolCallStart start -> {
                if (!toolCallIds.add(start.toolCallId())) {
                    throw invalid("stream tool call IDs must be unique");
                }
                openBlock(start.contentBlockIndex(),
                        new OpenBlock(ContentBlockType.TOOL_CALL, start.toolCallId(), new StringBuilder()));
            }
            case LlmStreamEvent.ToolCallDelta delta -> {
                OpenBlock block = requireToolCall(delta.contentBlockIndex(), delta.toolCallId());
                int length = delta.argumentsDelta().length();
                if (block.arguments().length() > LlmInputLimits.MAX_JSON_CHARACTERS - length) {
                    throw invalid("stream tool arguments exceed maximum JSON size");
                }
                if (bufferedArgumentCharacters > LlmInputLimits.MAX_PAYLOAD_CHARACTERS - length) {
                    throw invalid("stream pending tool arguments exceed maximum buffered size");
                }
                block.arguments().append(delta.argumentsDelta());
                bufferedArgumentCharacters += length;
            }
            case LlmStreamEvent.ToolCallEnd end -> {
                OpenBlock block = requireToolCall(end.contentBlockIndex(), end.toolCallId());
                validateArguments(block.arguments());
                bufferedArgumentCharacters -= block.arguments().length();
                openBlocks.remove(end.contentBlockIndex());
            }
            case LlmStreamEvent.UsageUpdate ignored -> { }
            case LlmStreamEvent.MessageEnd end -> {
                if (!openBlocks.isEmpty()) {
                    throw invalid("stream message cannot end with open content blocks");
                }
                if (end.finishReason() == FinishReason.TOOL_CALLS && toolCallIds.isEmpty()) {
                    throw invalid("tool call finish reason requires completed tool calls");
                }
                terminate();
            }
            case LlmStreamEvent.Error ignored -> terminate();
        }
    }

    private void openBlock(int index, OpenBlock block) {
        if (index != nextContentBlockIndex) {
            throw invalid("stream content blocks must start at consecutive indices from zero");
        }
        openBlocks.put(index, block);
        nextContentBlockIndex++;
    }

    private OpenBlock requireBlock(int index, ContentBlockType type) {
        OpenBlock block = openBlocks.get(index);
        if (block == null || block.type() != type) {
            throw invalid("stream delta or end must reference an open block of the matching type");
        }
        return block;
    }

    private OpenBlock requireToolCall(int index, String toolCallId) {
        OpenBlock block = requireBlock(index, ContentBlockType.TOOL_CALL);
        if (!block.toolCallId().equals(toolCallId)) {
            throw invalid("stream tool event must reference the block's tool call");
        }
        return block;
    }

    private static void validateArguments(StringBuilder arguments) {
        // A token walk avoids building a second argument tree and enforces the existing IR node/depth limits.
        try (var parser = ARGUMENT_JSON.createParser(arguments.toString())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalid("stream tool arguments must form one complete JSON object");
            }
            int depth = 1;
            int nodes = 1;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (depth == 0) {
                    throw invalid("stream tool arguments must form one complete JSON object");
                }
                if (token == JsonToken.FIELD_NAME) {
                    continue;
                }
                if (token.isStructEnd()) {
                    depth--;
                    continue;
                }
                if (++nodes > LlmInputLimits.MAX_JSON_NODES || depth + 1 > LlmInputLimits.MAX_JSON_DEPTH) {
                    throw invalid("stream tool arguments exceed maximum JSON depth or node count");
                }
                if (token.isStructStart()) {
                    depth++;
                }
            }
        } catch (IOException exception) {
            // Jackson errors can include raw argument text; never retain their message or cause.
            throw invalid("stream tool arguments must form one complete JSON object within the IR limits");
        }
    }

    private void terminate() {
        state = State.TERMINATED;
        discardBuffers();
    }

    private void discardBuffers() {
        openBlocks.clear();
        toolCallIds.clear();
        bufferedArgumentCharacters = 0;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record OpenBlock(ContentBlockType type, String toolCallId, StringBuilder arguments) {}

    private enum State { NEW, STARTED, TERMINATED, INVALID }
}
