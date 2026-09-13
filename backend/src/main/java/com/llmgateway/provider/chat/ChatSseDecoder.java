package com.llmgateway.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import java.util.*;
import reactor.core.publisher.Flux;
import static com.llmgateway.protocol.ProtocolJson.*;

public final class ChatSseDecoder {
    private final LlmStreamEventValidator validator = new LlmStreamEventValidator();
    private final Map<Integer, ToolState> tools = new LinkedHashMap<>();
    private String id;
    private Integer textIndex;
    private int nextIndex;
    private FinishReason finish;
    private boolean done;
    public static Flux<LlmStreamEvent> decode(Flux<String> frames) {
        return Flux.defer(() -> {
            ChatSseDecoder decoder = new ChatSseDecoder();
            return frames.concatMapIterable(decoder::accept, 1).concatWith(Flux.defer(() -> { decoder.complete(); return Flux.empty(); }))
                    .onErrorMap(e -> e instanceof GatewayException ? e : GatewayException.response());
        });
    }
    public List<LlmStreamEvent> accept(String frame) {
        try {
            if (done) throw GatewayException.response();
            List<LlmStreamEvent> events = new ArrayList<>();
            if (frame.equals("[DONE]")) {
                if (id == null || finish == null) throw GatewayException.response();
                if (textIndex != null) events.add(new LlmStreamEvent.ContentBlockEnd(id, textIndex));
                for (ToolState tool : tools.values()) {
                    open(tool, events);
                    events.add(new LlmStreamEvent.ToolCallEnd(id, tool.blockIndex, tool.id.toString()));
                }
                events.add(new LlmStreamEvent.MessageEnd(id, finish)); done = true;
            } else {
                JsonNode root = read(frame);
                if (root.has("error")) throw new GatewayException(GatewayError.PROVIDER_ERROR);
                String messageId = text(root, "id");
                if (id == null) { id = messageId; events.add(new LlmStreamEvent.MessageStart(id)); }
                else if (!id.equals(messageId)) throw GatewayException.response();
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.size() > 1) throw GatewayException.response();
                if (!choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    if (!choice.path("index").isIntegralNumber() || choice.get("index").intValue() != 0) throw GatewayException.response();
                    JsonNode delta = choice.get("delta");
                    if (delta == null || !delta.isObject()) throw GatewayException.response();
                    if (delta.hasNonNull("role") && !"assistant".equals(text(delta, "role"))) throw GatewayException.response();
                    if (delta.hasNonNull("reasoning_content") || delta.hasNonNull("refusal") || delta.hasNonNull("audio") || delta.hasNonNull("function_call")) throw GatewayException.response();
                    if (finish != null && (delta.hasNonNull("content") || delta.hasNonNull("tool_calls") || choice.hasNonNull("finish_reason"))) throw GatewayException.response();
                    if (delta.hasNonNull("content")) {
                        String value = text(delta, "content");
                        if (textIndex == null) { textIndex = nextIndex++; events.add(new LlmStreamEvent.ContentBlockStart(id, textIndex, ContentBlockType.TEXT)); }
                        events.add(new LlmStreamEvent.TextDelta(id, textIndex, value));
                    }
                    if (delta.hasNonNull("tool_calls")) {
                        if (!delta.get("tool_calls").isArray()) throw GatewayException.response();
                        for (JsonNode tool : delta.get("tool_calls")) tool(tool, events);
                    }
                    if (choice.hasNonNull("finish_reason")) finish = finish(text(choice, "finish_reason"));
                }
                Usage usage = usage(root.get("usage"));
                if (usage != null) events.add(new LlmStreamEvent.UsageUpdate(id, usage));
            }
            events.forEach(validator::accept);
            return events;
        } catch (IllegalArgumentException | NullPointerException ignored) { throw GatewayException.response(); }
        catch (GatewayException error) { throw error.error() == GatewayError.INVALID_REQUEST ? GatewayException.response() : error; }
    }
    private void tool(JsonNode input, List<LlmStreamEvent> events) {
        JsonNode indexNode = input.get("index");
        if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) throw GatewayException.response();
        int index = indexNode.intValue();
        if (index < 0 || index >= LlmInputLimits.MAX_TOOLS) throw GatewayException.response();
        if (input.hasNonNull("type") && !"function".equals(text(input, "type"))) throw GatewayException.response();
        ToolState state = tools.computeIfAbsent(index, ignored -> new ToolState());
        if (input.hasNonNull("id")) {
            if (state.blockIndex != null) { if (!state.id.toString().equals(text(input, "id"))) throw GatewayException.response(); }
            else state.id.append(text(input, "id"));
        }
        JsonNode function = input.get("function");
        if (function != null && !function.isObject()) throw GatewayException.response();
        if (function != null && function.hasNonNull("name")) {
            if (state.blockIndex != null) { if (!state.name.toString().equals(text(function, "name"))) throw GatewayException.response(); }
            else state.name.append(text(function, "name"));
        }
        if (state.id.length() > LlmInputLimits.MAX_IDENTIFIER_LENGTH || state.name.length() > LlmInputLimits.MAX_TOOL_NAME_LENGTH) throw GatewayException.response();
        if (function != null && function.hasNonNull("arguments")) {
            String arguments = text(function, "arguments");
            if (state.blockIndex == null) {
                state.pending.add(arguments);
                if (state.pending.size() > 32) throw GatewayException.response();
                if (!arguments.isEmpty()) open(state, events);
            } else events.add(new LlmStreamEvent.ToolCallDelta(id, state.blockIndex, state.id.toString(), arguments));
        }
    }
    private void open(ToolState tool, List<LlmStreamEvent> events) {
        if (tool.blockIndex != null) return;
        tool.blockIndex = nextIndex++;
        events.add(new LlmStreamEvent.ToolCallStart(id, tool.blockIndex, tool.id.toString(), tool.name.toString()));
        for (String arguments : tool.pending) events.add(new LlmStreamEvent.ToolCallDelta(id, tool.blockIndex, tool.id.toString(), arguments));
        tool.pending.clear();
    }
    public void complete() { if (!done) throw GatewayException.response(); validator.complete(); }
    private static final class ToolState {
        final StringBuilder id = new StringBuilder();
        final StringBuilder name = new StringBuilder();
        final List<String> pending = new ArrayList<>();
        Integer blockIndex;
    }
}
