package com.llmgateway.protocol.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.llmgateway.inference.GatewayException;
import com.llmgateway.model.*;
import com.llmgateway.protocol.*;
import com.llmgateway.protocol.chat.ChatRequest.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static com.llmgateway.protocol.ProtocolJson.*;

@Component
public class ChatCompletionsAdapter implements ClientProtocolAdapter<ChatRequest, ChatResponse> {
    private final Clock clock;
    public ChatCompletionsAdapter(Clock clock) { this.clock = clock; }

    @Override public LlmRequest parse(ChatRequest input, RequestContext context) {
        try {
            if (input.messages() == null || (input.n() != null && input.n() != 1)
                    || input.streamOptions() != null && (!Boolean.TRUE.equals(input.stream()) || !Boolean.TRUE.equals(input.streamOptions().includeUsage()))) throw GatewayException.invalid();
            List<Message> messages = input.messages().stream().map(this::message).toList();
            List<ToolDefinition> tools = input.tools() == null ? List.of() : input.tools().stream().map(tool -> {
                if (!"function".equals(tool.type()) || tool.function() == null) throw GatewayException.invalid();
                var f = tool.function();
                return new ToolDefinition(f.name(), f.description(), f.parameters());
            }).toList();
            return new LlmRequest(input.model(), messages, tools, choice(input.toolChoice()), null,
                    new GenerationConfig(input.temperature(), input.topP(), input.maxTokens(), input.seed(), strings(input.stop())),
                    format(input.responseFormat()), Boolean.TRUE.equals(input.stream()));
        } catch (IllegalArgumentException | NullPointerException ignored) { throw GatewayException.invalid(); }
    }

    private Message message(ChatMessage input) {
        MessageRole role;
        try { role = MessageRole.valueOf(input.role().toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception ignored) { throw GatewayException.invalid(); }
        // Wire roles are case sensitive.
        if (!role.name().toLowerCase(java.util.Locale.ROOT).equals(input.role())) throw GatewayException.invalid();
        List<ContentBlock> blocks = content(input.content());
        if (role != MessageRole.USER && blocks.stream().anyMatch(ContentBlock.Image.class::isInstance)) throw GatewayException.invalid();
        if (role == MessageRole.TOOL) {
            if (input.toolCalls() != null || blocks.stream().anyMatch(b -> !(b instanceof ContentBlock.Text))) throw GatewayException.invalid();
            return new Message(role, List.of(new ToolResult(input.toolCallId(), blocks, false)));
        }
        if (input.toolCallId() != null) throw GatewayException.invalid();
        if (input.toolCalls() != null) {
            for (ChatCall call : input.toolCalls()) {
                if (!"function".equals(call.type()) || call.function() == null) throw GatewayException.invalid();
                blocks.add(new ToolCall(call.id(), call.function().name(), arguments(call.function().arguments())));
            }
        }
        return new Message(role, blocks);
    }

    public static List<ContentBlock> content(JsonNode content) {
        var blocks = new ArrayList<ContentBlock>();
        if (content == null || content.isNull()) return blocks;
        if (content.isTextual()) { blocks.add(ContentBlock.text(content.textValue())); return blocks; }
        if (!content.isArray()) throw GatewayException.invalid();
        for (JsonNode part : content) {
            switch (text(part, "type")) {
                case "text" -> { fields(part, "type", "text"); blocks.add(ContentBlock.text(text(part, "text"))); }
                case "image_url" -> {
                    fields(part, "type", "image_url");
                    JsonNode media = part.get("image_url");
                    fields(media, "url", "detail");
                    if (media.has("detail") && !"auto".equals(text(media, "detail"))) throw GatewayException.invalid();
                    blocks.add(image(text(media, "url")));
                }
                default -> throw GatewayException.invalid();
            }
        }
        return blocks;
    }

    public static ToolChoice choice(JsonNode choice) {
        if (choice == null || choice.isNull()) return null;
        if (choice.isTextual()) return switch (choice.textValue()) {
            case "auto" -> ToolChoice.Mode.AUTO;
            case "none" -> ToolChoice.Mode.NONE;
            case "required" -> ToolChoice.Mode.REQUIRED;
            default -> throw GatewayException.invalid();
        };
        fields(choice, "type", "function");
        if (!"function".equals(text(choice, "type"))) throw GatewayException.invalid();
        fields(choice.get("function"), "name");
        return new ToolChoice.Named(text(choice.get("function"), "name"));
    }

    public static ResponseFormat format(JsonNode format) {
        if (format == null || format.isNull()) return null;
        String type = text(format, "type");
        if (type.equals("text") || type.equals("json_object")) {
            fields(format, "type");
            return type.equals("text") ? ResponseFormat.Mode.TEXT : ResponseFormat.Mode.JSON_OBJECT;
        }
        fields(format, "type", "json_schema");
        if (!type.equals("json_schema")) throw GatewayException.invalid();
        JsonNode schema = format.get("json_schema");
        fields(schema, "name", "description", "schema", "strict");
        if (schema.hasNonNull("strict") && !schema.get("strict").isBoolean()) throw GatewayException.invalid();
        return new ResponseFormat.JsonSchema(text(schema, "name"), optionalText(schema, "description"), schema.get("schema"),
                schema.hasNonNull("strict") ? schema.get("strict").booleanValue() : null);
    }

    public static JsonNode encodeFormat(ResponseFormat format) {
        if (format == null) return null;
        if (format instanceof ResponseFormat.Mode mode) return object().put("type", mode == ResponseFormat.Mode.TEXT ? "text" : "json_object");
        var schema = (ResponseFormat.JsonSchema) format;
        var value = object().put("name", schema.name()).set("schema", schema.schema());
        if (schema.description() != null) ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("description", schema.description());
        if (schema.strict() != null) ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("strict", schema.strict());
        return object().put("type", "json_schema").set("json_schema", value);
    }

    public static ChatRequest outbound(LlmRequest request, String actualModel) {
        List<ChatMessage> messages = new ArrayList<>();
        for (Message message : request.messages()) {
            if (message.role() == MessageRole.TOOL) {
                for (ContentBlock block : message.content()) {
                    ToolResult result = (ToolResult) block;
                    if (result.isError() || result.content().stream().anyMatch(b -> !(b instanceof ContentBlock.Text))) throw GatewayException.invalid();
                    String text = result.content().stream().map(b -> ((ContentBlock.Text) b).text()).collect(java.util.stream.Collectors.joining());
                    messages.add(new ChatMessage("tool", TextNode.valueOf(text), null, result.toolCallId()));
                }
            } else messages.add(encodeMessage(message.role().name().toLowerCase(java.util.Locale.ROOT), message.content()));
        }
        List<ChatTool> tools = request.tools().isEmpty() ? null : request.tools().stream().map(t -> new ChatTool("function",
                new FunctionDefinition(t.name(), t.description(), t.inputSchema()))).toList();
        JsonNode choice = null;
        if (tools != null) {
            choice = request.toolChoice() instanceof ToolChoice.Named named
                    ? object().put("type", "function").set("function", object().put("name", named.name()))
                    : TextNode.valueOf(((ToolChoice.Mode) request.toolChoice()).name().toLowerCase(java.util.Locale.ROOT));
        }
        GenerationConfig g = request.generation();
        return new ChatRequest(actualModel, messages, tools, choice, g == null ? null : g.temperature(), g == null ? null : g.topP(),
                g == null ? null : g.maxTokens(), g == null ? null : g.seed(),
                g == null || g.stopSequences().isEmpty() ? null : MAPPER.valueToTree(g.stopSequences()),
                encodeFormat(request.responseFormat()), request.stream(), null, request.stream() ? new StreamOptions(true) : null);
    }

    public static ChatMessage encodeMessage(String role, List<ContentBlock> blocks) {
        ArrayNode content = MAPPER.createArrayNode();
        var calls = new ArrayList<ChatCall>();
        for (ContentBlock block : blocks) {
            switch (block) {
                case ContentBlock.Text t -> content.add(object().put("type", "text").put("text", t.text()));
                case ContentBlock.Image i -> content.add(object().put("type", "image_url")
                        .set("image_url", object().put("url", mediaUrl(i.source()))));
                case ToolCall call -> calls.add(new ChatCall(call.id(), "function", new FunctionCall(call.name(), call.arguments().toString())));
                default -> throw GatewayException.invalid();
            }
        }
        JsonNode value = content.isEmpty() ? NullNode.instance : content;
        if (content.size() == 1 && content.get(0).get("type").asText().equals("text")) value = content.get(0).get("text");
        return new ChatMessage(role, value, calls.isEmpty() ? null : calls, null);
    }

    @Override public ChatResponse encode(LlmResponse response) {
        Usage u = response.usage();
        return new ChatResponse(response.id(), "chat.completion", clock.instant().getEpochSecond(), response.model(),
                List.of(new ChatResponse.Choice(0, encodeMessage("assistant", response.content()), chatFinish(response.finishReason()))),
                u == null ? null : new ChatResponse.TokenUsage(u.inputTokens(), u.outputTokens(), u.totalTokens()));
    }
    @Override public Flux<ServerSentEvent<String>> encodeStream(Flux<LlmStreamEvent> events, String model) {
        return ClientSseEncoder.chat(events, model, clock);
    }
}
