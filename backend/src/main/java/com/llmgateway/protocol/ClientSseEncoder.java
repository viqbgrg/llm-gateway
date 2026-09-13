package com.llmgateway.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.inference.GatewayException;
import com.llmgateway.model.*;
import com.llmgateway.protocol.anthropic.AnthropicAdapter;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.time.Clock;
import java.util.*;
import static com.llmgateway.protocol.ProtocolJson.*;

/** One encoder state per subscription. Responses retains bounded text only for required terminal items. */
public final class ClientSseEncoder {
    private ClientSseEncoder() {}
    public static Flux<ServerSentEvent<String>> chat(Flux<LlmStreamEvent> source, String model, Clock clock) {
        return Flux.defer(() -> {
            Map<Integer, Integer> toolIndices = new HashMap<>();
            long created = clock.instant().getEpochSecond();
            return source.concatMapIterable(event -> {
                ObjectNode delta = object(); String finish = null; Usage usage = null;
                switch (event) {
                    case LlmStreamEvent.MessageStart ignored -> delta.put("role", "assistant").put("content", "");
                    case LlmStreamEvent.TextDelta text -> delta.put("content", text.text());
                    case LlmStreamEvent.ToolCallStart tool -> {
                        int index = toolIndices.size(); toolIndices.put(tool.contentBlockIndex(), index);
                        delta.set("tool_calls", MAPPER.createArrayNode().add(object().put("index", index).put("id", tool.toolCallId())
                                .put("type", "function").set("function", object().put("name", tool.toolName()).put("arguments", ""))));
                    }
                    case LlmStreamEvent.ToolCallDelta tool -> delta.set("tool_calls", MAPPER.createArrayNode()
                            .add(object().put("index", toolIndices.get(tool.contentBlockIndex())).set("function", object().put("arguments", tool.argumentsDelta()))));
                    case LlmStreamEvent.UsageUpdate u -> usage = u.usage();
                    case LlmStreamEvent.MessageEnd end -> finish = chatFinish(end.finishReason());
                    case LlmStreamEvent.Error error -> { return List.of(data(object().set("error", object().put("code", error.error().code()).put("message", error.error().message())))); }
                    case LlmStreamEvent.ThinkingDelta ignored -> throw GatewayException.response();
                    default -> { return List.of(); }
                }
                ObjectNode chunk = object().put("id", event.messageId()).put("object", "chat.completion.chunk").put("created", created).put("model", model);
                var choices = MAPPER.createArrayNode();
                if (usage == null) choices.add(object().put("index", 0).set("delta", delta));
                if (!choices.isEmpty()) ((ObjectNode) choices.get(0)).put("finish_reason", finish);
                chunk.set("choices", choices);
                if (usage != null) chunk.set("usage", usage(usage));
                return event instanceof LlmStreamEvent.MessageEnd ? List.of(data(chunk), ServerSentEvent.<String>builder("[DONE]").build()) : List.of(data(chunk));
            }, 1);
        });
    }
    public static Flux<ServerSentEvent<String>> anthropic(Flux<LlmStreamEvent> source, String model) {
        return Flux.defer(() -> {
            Usage[] usage = {null};
            return source.concatMapIterable(event -> {
                switch (event) {
                    case LlmStreamEvent.MessageStart start -> {
                        ObjectNode message = object().put("id", start.messageId()).put("type", "message").put("role", "assistant")
                                .put("model", model).putNull("stop_reason").putNull("stop_sequence");
                        message.set("content", MAPPER.createArrayNode());
                        message.set("usage", anthropicUsage(null));
                        return List.of(named("message_start", object().set("message", message)));
                    }
                    case LlmStreamEvent.ContentBlockStart start -> {
                        if (start.contentType() != ContentBlockType.TEXT) throw GatewayException.response();
                        return List.of(named("content_block_start", object().put("index", start.contentBlockIndex())
                                .set("content_block", object().put("type", "text").put("text", ""))));
                    }
                    case LlmStreamEvent.TextDelta delta -> { return List.of(named("content_block_delta", object().put("index", delta.contentBlockIndex())
                            .set("delta", object().put("type", "text_delta").put("text", delta.text())))); }
                    case LlmStreamEvent.ToolCallStart start -> { return List.of(named("content_block_start", object().put("index", start.contentBlockIndex())
                            .set("content_block", object().put("type", "tool_use").put("id", start.toolCallId()).put("name", start.toolName()).set("input", object())))); }
                    case LlmStreamEvent.ToolCallDelta delta -> { return List.of(named("content_block_delta", object().put("index", delta.contentBlockIndex())
                            .set("delta", object().put("type", "input_json_delta").put("partial_json", delta.argumentsDelta())))); }
                    case LlmStreamEvent.ContentBlockEnd end -> { return List.of(named("content_block_stop", object().put("index", end.contentBlockIndex()))); }
                    case LlmStreamEvent.ToolCallEnd end -> { return List.of(named("content_block_stop", object().put("index", end.contentBlockIndex()))); }
                    case LlmStreamEvent.UsageUpdate update -> { usage[0] = update.usage(); return List.of(); }
                    case LlmStreamEvent.MessageEnd end -> {
                        ObjectNode message = object().set("delta", object().put("stop_reason", AnthropicAdapter.stopReason(end.finishReason())).putNull("stop_sequence"));
                        message.set("usage", anthropicUsage(usage[0]));
                        return List.of(named("message_delta", message), named("message_stop", object()));
                    }
                    case LlmStreamEvent.Error error -> { return List.of(named("error", object().set("error", object().put("type", error.error().code()).put("message", error.error().message())))); }
                    default -> throw GatewayException.response();
                }
            }, 1);
        });
    }
    public static Flux<ServerSentEvent<String>> responses(Flux<LlmStreamEvent> source, String model, Clock clock) {
        return Flux.defer(() -> {
            ResponsesState state = new ResponsesState(model, clock);
            return source.concatMapIterable(state::encode, 1);
        });
    }
    public static boolean meaningful(LlmStreamEvent event) {
        return event instanceof LlmStreamEvent.TextDelta t && !t.text().isEmpty()
                || event instanceof LlmStreamEvent.ThinkingDelta thinking && !thinking.text().isEmpty()
                || event instanceof LlmStreamEvent.ToolCallStart
                || event instanceof LlmStreamEvent.MessageEnd;
    }
    public static boolean content(LlmStreamEvent event) { return meaningful(event) && !(event instanceof LlmStreamEvent.MessageEnd); }
    private static ObjectNode anthropicUsage(Usage usage) {
        // SDK stream accumulators need a usage object even before Chat reports it.
        // Null preserves unknown counters; zero would invent billing information.
        return object().put("input_tokens", usage == null ? null : usage.inputTokens())
                .put("output_tokens", usage == null ? null : usage.outputTokens());
    }
    private static ServerSentEvent<String> data(JsonNode value) { return ServerSentEvent.<String>builder(value.toString()).build(); }
    private static ServerSentEvent<String> named(String event, ObjectNode value) { value.put("type", event); return ServerSentEvent.<String>builder(value.toString()).event(event).build(); }

    private static final class ResponsesState {
        private final String model;
        private final long created;
        private final Map<Integer, ObjectNode> items = new TreeMap<>();
        private final Map<Integer, StringBuilder> content = new HashMap<>();
        private String id;
        private Usage usage;
        private int sequence;
        private int characters;
        ResponsesState(String model, Clock clock) { this.model = model; created = clock.instant().getEpochSecond(); }
        private ServerSentEvent<String> event(String name, ObjectNode node) { node.put("sequence_number", sequence++); return named(name, node); }
        private ObjectNode response(String status) {
            ObjectNode response = object().put("id", id).put("object", "response").put("created_at", created).put("status", status).put("model", model).put("store", false);
            response.set("output", MAPPER.valueToTree(items.values()));
            if (usage != null) {
                ObjectNode counts = object();
                if (usage.inputTokens() != null) counts.put("input_tokens", usage.inputTokens());
                if (usage.outputTokens() != null) counts.put("output_tokens", usage.outputTokens());
                if (usage.totalTokens() != null) counts.put("total_tokens", usage.totalTokens());
                response.set("usage", counts);
            }
            return response;
        }
        private ObjectNode position(int index) { return object().put("item_id", items.get(index).get("id").asText()).put("output_index", index); }
        private void append(int index, String value) {
            characters += value.length();
            if (characters > LlmInputLimits.MAX_PAYLOAD_CHARACTERS) throw GatewayException.response();
            content.get(index).append(value);
        }
        List<ServerSentEvent<String>> encode(LlmStreamEvent event) {
            switch (event) {
                case LlmStreamEvent.MessageStart start -> {
                    id = start.messageId();
                    return List.of(event("response.created", object().set("response", response("in_progress"))),
                            event("response.in_progress", object().set("response", response("in_progress"))));
                }
                case LlmStreamEvent.ContentBlockStart start -> {
                    if (start.contentType() != ContentBlockType.TEXT) throw GatewayException.response();
                    int i = start.contentBlockIndex(); content.put(i, new StringBuilder());
                    ObjectNode item = object().put("id", "msg_" + id + "_" + i).put("type", "message").put("role", "assistant").put("status", "in_progress");
                    item.set("content", MAPPER.createArrayNode()); items.put(i, item);
                    var added = event("response.output_item.added", object().put("output_index", i).set("item", item.deepCopy()));
                    ObjectNode part = object().put("type", "output_text").put("text", ""); part.set("annotations", MAPPER.createArrayNode());
                    return List.of(added, event("response.content_part.added", position(i).put("content_index", 0).set("part", part)));
                }
                case LlmStreamEvent.TextDelta delta -> {
                    append(delta.contentBlockIndex(), delta.text());
                    return List.of(event("response.output_text.delta", position(delta.contentBlockIndex()).put("content_index", 0).put("delta", delta.text())));
                }
                case LlmStreamEvent.ToolCallStart start -> {
                    int i = start.contentBlockIndex(); content.put(i, new StringBuilder());
                    ObjectNode item = object().put("id", "fc_" + id + "_" + i).put("type", "function_call").put("call_id", start.toolCallId())
                            .put("name", start.toolName()).put("arguments", "").put("status", "in_progress");
                    items.put(i, item);
                    return List.of(event("response.output_item.added", object().put("output_index", i).set("item", item.deepCopy())));
                }
                case LlmStreamEvent.ToolCallDelta delta -> {
                    append(delta.contentBlockIndex(), delta.argumentsDelta());
                    return List.of(event("response.function_call_arguments.delta", position(delta.contentBlockIndex()).put("delta", delta.argumentsDelta())));
                }
                case LlmStreamEvent.ContentBlockEnd end -> {
                    int i = end.contentBlockIndex(); String value = content.remove(i).toString();
                    ObjectNode part = object().put("type", "output_text").put("text", value); part.set("annotations", MAPPER.createArrayNode());
                    ObjectNode item = items.get(i); item.put("status", "completed"); item.set("content", MAPPER.createArrayNode().add(part));
                    return List.of(event("response.output_text.done", position(i).put("content_index", 0).put("text", value)),
                            event("response.content_part.done", position(i).put("content_index", 0).set("part", part)),
                            event("response.output_item.done", object().put("output_index", i).set("item", item.deepCopy())));
                }
                case LlmStreamEvent.ToolCallEnd end -> {
                    int i = end.contentBlockIndex(); String value = content.remove(i).toString();
                    ObjectNode item = items.get(i); item.put("arguments", value).put("status", "completed");
                    return List.of(event("response.function_call_arguments.done", position(i).put("arguments", value)),
                            event("response.output_item.done", object().put("output_index", i).set("item", item.deepCopy())));
                }
                case LlmStreamEvent.UsageUpdate update -> { usage = update.usage(); return List.of(); }
                case LlmStreamEvent.MessageEnd end -> {
                    boolean incomplete = end.finishReason() == FinishReason.LENGTH || end.finishReason() == FinishReason.CONTENT_FILTER;
                    String status = incomplete ? "incomplete" : "completed";
                    ObjectNode response = response(status);
                    if (incomplete) response.set("incomplete_details", object().put("reason", end.finishReason() == FinishReason.LENGTH ? "max_output_tokens" : "content_filter"));
                    return List.of(event("response." + status, object().set("response", response)));
                }
                case LlmStreamEvent.Error error -> {
                    ObjectNode response = response("failed"); response.set("error", object().put("code", error.error().code()).put("message", error.error().message()));
                    return List.of(event("response.failed", object().set("response", response)));
                }
                default -> throw GatewayException.response();
            }
        }
    }
}
