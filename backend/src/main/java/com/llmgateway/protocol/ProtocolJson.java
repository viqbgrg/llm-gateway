package com.llmgateway.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.inference.GatewayException;
import com.llmgateway.model.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ProtocolJson {
    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(80)
                    .maxStringLength(8 * 1024 * 1024).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private ProtocolJson() {}
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static JsonNode read(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception ignored) { throw GatewayException.invalid(); }
    }
    public static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject()) throw GatewayException.invalid();
        Set<String> names = Set.of(allowed);
        node.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw GatewayException.invalid(); });
    }
    public static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw GatewayException.invalid();
        return node.textValue();
    }
    public static String text(JsonNode node, String field) { return text(node.get(field)); }
    public static String optionalText(JsonNode node, String field) {
        return node.hasNonNull(field) ? text(node, field) : null;
    }
    public static Long count(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw GatewayException.invalid();
        return value.longValue();
    }
    public static List<String> strings(JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        if (value.isTextual()) return List.of(value.textValue());
        if (!value.isArray()) throw GatewayException.invalid();
        List<String> result = new ArrayList<>();
        value.forEach(item -> result.add(text(item)));
        return result;
    }
    public static JsonNode arguments(String input) {
        JsonNode value = read(input);
        if (!value.isObject()) throw GatewayException.invalid();
        return value;
    }
    public static ContentBlock.Image image(String url) {
        if (url.startsWith("data:")) {
            int separator = url.indexOf(";base64,");
            if (separator < 5) throw GatewayException.invalid();
            return new ContentBlock.Image(new MediaSource.InlineData(url.substring(separator + 8), url.substring(5, separator)));
        }
        return new ContentBlock.Image(new MediaSource.Url(URI.create(url), null));
    }
    public static String mediaUrl(MediaSource source) {
        return switch (source) {
            case MediaSource.Url url -> url.url().toString();
            case MediaSource.InlineData data -> "data:" + data.mimeType() + ";base64," + data.data();
        };
    }
    public static FinishReason finish(String reason) {
        return switch (reason) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> throw GatewayException.response();
        };
    }
    public static String chatFinish(FinishReason reason) {
        return switch (reason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_CALLS -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            case UNKNOWN -> throw GatewayException.response();
        };
    }
    public static Usage usage(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw GatewayException.response();
        return new Usage(count(node, "prompt_tokens"), count(node, "completion_tokens"), count(node, "total_tokens"));
    }
    public static ObjectNode usage(Usage usage) {
        ObjectNode value = object();
        if (usage.inputTokens() != null) value.put("prompt_tokens", usage.inputTokens());
        if (usage.outputTokens() != null) value.put("completion_tokens", usage.outputTokens());
        if (usage.totalTokens() != null) value.put("total_tokens", usage.totalTokens());
        return value;
    }
}
