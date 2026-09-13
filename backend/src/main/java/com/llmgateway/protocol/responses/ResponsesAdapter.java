package com.llmgateway.protocol.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmgateway.inference.GatewayException;
import com.llmgateway.model.*;
import com.llmgateway.protocol.*;
import com.llmgateway.protocol.chat.ChatCompletionsAdapter;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import static com.llmgateway.protocol.ProtocolJson.*;

@Component
public class ResponsesAdapter implements ClientProtocolAdapter<ResponsesRequest, ResponsesResponse> {
    private final Clock clock;
    public ResponsesAdapter(Clock clock) { this.clock = clock; }
    @Override public LlmRequest parse(ResponsesRequest input, RequestContext context) {
        try {
            if (Boolean.TRUE.equals(input.store()) || input.input() == null) throw GatewayException.invalid();
            List<Message> messages = new ArrayList<>();
            if (input.instructions() != null) messages.add(new Message(MessageRole.SYSTEM, List.of(ContentBlock.text(input.instructions()))));
            if (input.input().isTextual()) messages.add(new Message(MessageRole.USER, List.of(ContentBlock.text(input.input().textValue()))));
            else {
                if (!input.input().isArray()) throw GatewayException.invalid();
                for (JsonNode item : input.input()) addItem(messages, item);
            }
            List<ToolDefinition> tools = new ArrayList<>();
            if (input.tools() != null) for (JsonNode tool : input.tools()) {
                fields(tool, "type", "name", "description", "parameters");
                if (!"function".equals(text(tool, "type"))) throw GatewayException.invalid();
                tools.add(new ToolDefinition(text(tool, "name"), optionalText(tool, "description"), tool.get("parameters")));
            }
            ToolChoice choice;
            if (input.toolChoice() == null || input.toolChoice().isTextual()) choice = ChatCompletionsAdapter.choice(input.toolChoice());
            else {
                fields(input.toolChoice(), "type", "name");
                if (!"function".equals(text(input.toolChoice(), "type"))) throw GatewayException.invalid();
                choice = new ToolChoice.Named(text(input.toolChoice(), "name"));
            }
            ResponseFormat format = null;
            if (input.text() != null) {
                fields(input.text(), "format");
                JsonNode f = input.text().get("format");
                String type = text(f, "type");
                if (type.equals("json_schema")) {
                    fields(f, "type", "name", "description", "schema", "strict");
                    var schema = f.deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) schema).remove("type");
                    format = ChatCompletionsAdapter.format(object().put("type", type).set("json_schema", schema));
                } else format = ChatCompletionsAdapter.format(f);
            }
            return new LlmRequest(input.model(), messages, tools, choice, null,
                    new GenerationConfig(input.temperature(), input.topP(), input.maxOutputTokens(), null), format,
                    Boolean.TRUE.equals(input.stream()));
        } catch (IllegalArgumentException | NullPointerException ignored) { throw GatewayException.invalid(); }
    }
    private static void addItem(List<Message> messages, JsonNode item) {
        String type = item.has("type") ? text(item, "type") : "message";
        if (type.equals("function_call")) {
            fields(item, "type", "call_id", "name", "arguments", "id", "status");
            historyMetadata(item);
            ToolCall call = new ToolCall(text(item, "call_id"), text(item, "name"), arguments(text(item, "arguments")));
            addMessage(messages, MessageRole.ASSISTANT, List.of(call));
        } else if (type.equals("function_call_output")) {
            fields(item, "type", "call_id", "output");
            messages.add(new Message(MessageRole.TOOL, List.of(new ToolResult(text(item, "call_id"),
                    List.of(ContentBlock.text(text(item, "output"))), false))));
        } else if (type.equals("message")) {
            fields(item, "type", "role", "content", "id", "status");
            historyMetadata(item);
            MessageRole role = switch (text(item, "role")) {
                case "user" -> MessageRole.USER;
                case "assistant" -> MessageRole.ASSISTANT;
                case "system" -> MessageRole.SYSTEM;
                case "developer" -> MessageRole.DEVELOPER;
                default -> throw GatewayException.invalid();
            };
            JsonNode content = item.get("content");
            List<ContentBlock> blocks = new ArrayList<>();
            if (content != null && content.isTextual()) blocks.add(ContentBlock.text(content.textValue()));
            else {
                if (content == null || !content.isArray()) throw GatewayException.invalid();
                for (JsonNode block : content) {
                    String kind = text(block, "type");
                    if (kind.equals("input_text")) {
                        fields(block, "type", "text"); blocks.add(ContentBlock.text(text(block, "text")));
                    } else if (kind.equals("output_text") && role == MessageRole.ASSISTANT) {
                        fields(block, "type", "text", "annotations");
                        JsonNode annotations = block.get("annotations");
                        if (annotations != null && !annotations.isNull() && (!annotations.isArray() || !annotations.isEmpty())) throw GatewayException.invalid();
                        blocks.add(ContentBlock.text(text(block, "text")));
                    } else if (kind.equals("input_image") && role == MessageRole.USER) {
                        fields(block, "type", "image_url", "detail");
                        if (block.has("detail") && !"auto".equals(text(block, "detail"))) throw GatewayException.invalid();
                        blocks.add(image(text(block, "image_url")));
                    } else throw GatewayException.invalid();
                }
            }
            addMessage(messages, role, blocks);
        } else throw GatewayException.invalid();
    }
    private static void historyMetadata(JsonNode item) {
        String id = optionalText(item, "id");
        if (id != null && (id.isBlank() || id.length() > 512)) throw GatewayException.invalid();
        String status = optionalText(item, "status");
        if (status != null && !status.equals("completed") && !status.equals("incomplete")) throw GatewayException.invalid();
    }
    private static void addMessage(List<Message> messages, MessageRole role, List<ContentBlock> blocks) {
        // Responses can split one assistant turn into text and function-call items.
        if (role == MessageRole.ASSISTANT && !messages.isEmpty() && messages.getLast().role() == role) {
            var combined = new ArrayList<>(messages.removeLast().content());
            combined.addAll(blocks);
            messages.add(new Message(role, combined));
        } else messages.add(new Message(role, blocks));
    }
    @Override public ResponsesResponse encode(LlmResponse response) {
        var output = new ArrayList<JsonNode>();
        int index = 0;
        for (ContentBlock b : response.content()) {
            if (b instanceof ContentBlock.Text t) {
                var content = MAPPER.createArrayNode().add(object().put("type", "output_text").put("text", t.text())
                        .set("annotations", MAPPER.createArrayNode()));
                output.add(object().put("id", "msg_" + response.id() + "_" + index++).put("type", "message")
                        .put("status", "completed").put("role", "assistant").set("content", content));
            } else if (b instanceof ToolCall t) {
                output.add(object().put("id", "fc_" + response.id() + "_" + index++).put("type", "function_call")
                        .put("call_id", t.id()).put("name", t.name()).put("arguments", t.arguments().toString()).put("status", "completed"));
            } else throw GatewayException.response();
        }
        boolean incomplete = response.finishReason() == FinishReason.LENGTH || response.finishReason() == FinishReason.CONTENT_FILTER;
        Usage u = response.usage();
        return new ResponsesResponse(response.id(), "response", clock.instant().getEpochSecond(), incomplete ? "incomplete" : "completed",
                response.model(), output, null, incomplete ? object().put("reason", response.finishReason() == FinishReason.LENGTH ? "max_output_tokens" : "content_filter") : null,
                u == null ? null : new ResponsesResponse.TokenUsage(u.inputTokens(), u.outputTokens(), u.totalTokens()), false);
    }
    @Override public Flux<ServerSentEvent<String>> encodeStream(Flux<LlmStreamEvent> events, String model) {
        return ClientSseEncoder.responses(events, model, clock);
    }
}
