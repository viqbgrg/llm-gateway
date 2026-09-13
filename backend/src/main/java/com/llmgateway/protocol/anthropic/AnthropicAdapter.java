package com.llmgateway.protocol.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmgateway.inference.GatewayException;
import com.llmgateway.model.*;
import com.llmgateway.protocol.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import static com.llmgateway.protocol.ProtocolJson.*;

@Component
public class AnthropicAdapter implements ClientProtocolAdapter<AnthropicRequest, AnthropicResponse> {
    @Override public LlmRequest parse(AnthropicRequest input, RequestContext context) {
        try {
            if (input.maxTokens() == null || input.messages() == null) throw GatewayException.invalid();
            List<Message> messages = new ArrayList<>();
            if (input.system() != null && !input.system().isNull()) {
                List<ContentBlock> system = blocks(input.system());
                if (system.stream().anyMatch(b -> !(b instanceof ContentBlock.Text))) throw GatewayException.invalid();
                messages.add(new Message(MessageRole.SYSTEM, system));
            }
            for (var message : input.messages()) {
                MessageRole role = switch (message.role()) {
                    case "user" -> MessageRole.USER;
                    case "assistant" -> MessageRole.ASSISTANT;
                    default -> throw GatewayException.invalid();
                };
                List<ContentBlock> batch = new ArrayList<>();
                MessageRole batchRole = role;
                for (ContentBlock block : blocks(message.content())) {
                    if (block instanceof ContentBlock.Image && role != MessageRole.USER) throw GatewayException.invalid();
                    MessageRole next = block instanceof ToolResult ? MessageRole.TOOL : role;
                    if (next == MessageRole.TOOL && role != MessageRole.USER) throw GatewayException.invalid();
                    if (batchRole != next && !batch.isEmpty()) { messages.add(new Message(batchRole, batch)); batch = new ArrayList<>(); }
                    batchRole = next;
                    batch.add(block);
                }
                messages.add(new Message(batchRole, batch));
            }
            List<ToolDefinition> tools = input.tools() == null ? List.of() : input.tools().stream()
                    .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema())).toList();
            ToolChoice choice = null;
            if (input.toolChoice() != null) {
                fields(input.toolChoice(), "type", "name");
                String type = text(input.toolChoice(), "type");
                if (!type.equals("tool") && input.toolChoice().has("name")) throw GatewayException.invalid();
                choice = switch (type) {
                    case "auto" -> ToolChoice.Mode.AUTO;
                    case "any" -> ToolChoice.Mode.REQUIRED;
                    case "none" -> ToolChoice.Mode.NONE;
                    case "tool" -> new ToolChoice.Named(text(input.toolChoice(), "name"));
                    default -> throw GatewayException.invalid();
                };
            }
            return new LlmRequest(input.model(), messages, tools, choice, null,
                    new GenerationConfig(input.temperature(), input.topP(), input.maxTokens(), null, input.stopSequences()),
                    null, Boolean.TRUE.equals(input.stream()));
        } catch (IllegalArgumentException | NullPointerException ignored) { throw GatewayException.invalid(); }
    }
    private static List<ContentBlock> blocks(JsonNode input) {
        if (input == null) throw GatewayException.invalid();
        if (input.isTextual()) return List.of(ContentBlock.text(input.textValue()));
        if (!input.isArray()) throw GatewayException.invalid();
        var result = new ArrayList<ContentBlock>();
        for (JsonNode b : input) {
            switch (text(b, "type")) {
                case "text" -> { fields(b, "type", "text"); result.add(ContentBlock.text(text(b, "text"))); }
                case "image" -> {
                    fields(b, "type", "source");
                    JsonNode source = b.get("source");
                    String type = text(source, "type");
                    if (type.equals("base64")) {
                        fields(source, "type", "media_type", "data");
                        result.add(new ContentBlock.Image(new MediaSource.InlineData(text(source, "data"), text(source, "media_type"))));
                    } else if (type.equals("url")) {
                        fields(source, "type", "url");
                        result.add(new ContentBlock.Image(new MediaSource.Url(URI.create(text(source, "url")), null)));
                    } else throw GatewayException.invalid();
                }
                case "tool_use" -> {
                    fields(b, "type", "id", "name", "input");
                    result.add(new ToolCall(text(b, "id"), text(b, "name"), b.get("input")));
                }
                case "tool_result" -> {
                    fields(b, "type", "tool_use_id", "content", "is_error");
                    if (b.has("is_error") && (!b.get("is_error").isBoolean() || b.get("is_error").booleanValue())) throw GatewayException.invalid();
                    List<ContentBlock> content = b.has("content") ? blocks(b.get("content")) : List.of();
                    if (content.stream().anyMatch(c -> !(c instanceof ContentBlock.Text))) throw GatewayException.invalid();
                    result.add(new ToolResult(text(b, "tool_use_id"), content, false));
                }
                default -> throw GatewayException.invalid();
            }
        }
        return result;
    }
    public static String stopReason(FinishReason reason) {
        return switch (reason) {
            case STOP -> "end_turn";
            case LENGTH -> "max_tokens";
            case TOOL_CALLS -> "tool_use";
            case CONTENT_FILTER -> "refusal";
            case UNKNOWN -> throw GatewayException.response();
        };
    }
    @Override public AnthropicResponse encode(LlmResponse response) {
        List<JsonNode> content = response.content().stream().map(b -> (JsonNode) switch (b) {
            case ContentBlock.Text t -> object().put("type", "text").put("text", t.text());
            case ToolCall t -> object().put("type", "tool_use").put("id", t.id()).put("name", t.name()).set("input", t.arguments());
            default -> throw GatewayException.response();
        }).toList();
        Usage u = response.usage();
        return new AnthropicResponse(response.id(), "message", "assistant", response.model(), content,
                stopReason(response.finishReason()), null, u == null ? null : new AnthropicResponse.TokenUsage(u.inputTokens(), u.outputTokens()));
    }
    @Override public Flux<ServerSentEvent<String>> encodeStream(Flux<LlmStreamEvent> events, String model) {
        return ClientSseEncoder.anthropic(events, model);
    }
}
