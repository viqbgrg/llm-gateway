package com.llmgateway.protocol;

import com.llmgateway.inference.GatewayException;
import com.llmgateway.model.*;
import com.llmgateway.provider.chat.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static com.llmgateway.protocol.ProtocolJson.*;

class StreamingProtocolTest {
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 7, 64, 4096})
    void decodesTheSameUtf8EventsForEveryNetworkFragmentation(int size) throws Exception {
        byte[] bytes = ProtocolAdaptersTest.fixture("chat-stream.sse").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        List<byte[]> parts = new ArrayList<>();
        for (int offset = 0; offset < bytes.length; offset += size) parts.add(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + size)));
        var events = ChatSseDecoder.decode(SseFramer.decode(Flux.fromIterable(parts).map(DefaultDataBufferFactory.sharedInstance::wrap), 4096)).collectList().block();
        assertThat(events).extracting(e -> e.type().name()).containsExactlyElementsOf(strings(read(ProtocolAdaptersTest.fixture("stream-events.json"))));
        assertThat(events.stream().filter(LlmStreamEvent.TextDelta.class::isInstance).map(LlmStreamEvent.TextDelta.class::cast).findFirst().orElseThrow().text()).isEqualTo("你好");
    }
    @Test void acceptsMultilineDataCommentsAndCrLineEndings() {
        var parser = new SseFramer(1024);
        assertThat(parser.accept(": heartbeat\rid: ignored\rdata: {\rdata: \"x\":1}\r\r".getBytes(StandardCharsets.UTF_8))).containsExactly("{\n\"x\":1}");
        parser.complete();
    }
    @Test void rejectsMalformedUtf8OversizedFramesAndIncompleteFraming() {
        assertThatThrownBy(() -> new SseFramer(100).accept(new byte[]{(byte) 0xc3, (byte) 0x28, 10})).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> new SseFramer(10).accept("data: long invalid frame\n\n".getBytes(StandardCharsets.UTF_8))).isInstanceOf(GatewayException.class);
        var parser = new SseFramer(100); parser.accept("data: unfinished\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(parser::complete).isInstanceOf(GatewayException.class);
    }
    @Test void assemblesToolMetadataAndInterleavesUnclosedArguments() {
        var first = object().set("tool_calls", MAPPER.createArrayNode().add(tool(0, "call_", "look", "")));
        var second = object().set("tool_calls", MAPPER.createArrayNode().add(tool(0, "a", "up", "{\"x\":"))
                .add(tool(1, "call_b", "other", "{")));
        var third = object().set("tool_calls", MAPPER.createArrayNode().add(tool(1, null, null, "}"))
                .add(tool(0, null, null, "1}")));
        var events = ChatSseDecoder.decode(Flux.just(chunk(first, null), chunk(second, null), chunk(third, "tool_calls"), "[DONE]")).collectList().block();
        assertThat(events.stream().filter(LlmStreamEvent.ToolCallStart.class::isInstance).map(LlmStreamEvent.ToolCallStart.class::cast).map(LlmStreamEvent.ToolCallStart::toolCallId)).containsExactly("call_a", "call_b");
        assertThat(events.getLast()).isEqualTo(new LlmStreamEvent.MessageEnd("s", FinishReason.TOOL_CALLS));
    }
    @Test void rejectsPrematureEofAndIncompleteToolJson() {
        StepVerifier.create(ChatSseDecoder.decode(Flux.just(chunk(object().put("content", "text"), "stop")))).expectNextCount(3).expectError(GatewayException.class).verify();
        var tool = object().set("tool_calls", MAPPER.createArrayNode().add(tool(0, "c", "f", "{")));
        StepVerifier.create(ChatSseDecoder.decode(Flux.just(chunk(tool, "tool_calls"), "[DONE]"))).expectNextCount(3).expectError(GatewayException.class).verify();
    }
    @Test void emitsEachClientsRequiredEndEventsAndDoesNotCountMetadataAsContent() {
        var events = List.<LlmStreamEvent>of(new LlmStreamEvent.MessageStart("m"), new LlmStreamEvent.ContentBlockStart("m", 0, ContentBlockType.TEXT),
                new LlmStreamEvent.TextDelta("m", 0, "hello"), new LlmStreamEvent.ContentBlockEnd("m", 0), new LlmStreamEvent.MessageEnd("m", FinishReason.STOP));
        var chat = ClientSseEncoder.chat(Flux.fromIterable(events), "public", Clock.systemUTC()).collectList().block();
        assertThat(chat.getLast().data()).isEqualTo("[DONE]");
        var anthropic = ClientSseEncoder.anthropic(Flux.fromIterable(events), "public").collectList().block();
        assertThat(anthropic).extracting(e -> e.event()).containsExactly("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop");
        var responses = ClientSseEncoder.responses(Flux.fromIterable(events), "public", Clock.systemUTC()).collectList().block();
        assertThat(responses.getLast().event()).isEqualTo("response.completed");
        assertThat(read(responses.getLast().data()).get("response").get("output").get(0).get("content").get(0).get("text").asText()).isEqualTo("hello");
        for (int i = 0; i < responses.size(); i++) assertThat(read(responses.get(i).data()).get("sequence_number").asInt()).isEqualTo(i);
        assertThat(ClientSseEncoder.meaningful(events.getFirst())).isFalse();
        assertThat(ClientSseEncoder.content(events.getLast())).isFalse();
    }
    @ParameterizedTest @MethodSource("usageSamples")
    void alwaysEmitsMergeableAnthropicUsageWithoutInventingTokenCounts(Usage usage) {
        List<LlmStreamEvent> events = new ArrayList<>(List.of(new LlmStreamEvent.MessageStart("m"),
                new LlmStreamEvent.ContentBlockStart("m", 0, ContentBlockType.TEXT),
                new LlmStreamEvent.TextDelta("m", 0, "answer"), new LlmStreamEvent.ContentBlockEnd("m", 0)));
        if (usage != null) events.add(new LlmStreamEvent.UsageUpdate("m", usage));
        events.add(new LlmStreamEvent.MessageEnd("m", FinishReason.STOP));
        var wire = ClientSseEncoder.anthropic(Flux.fromIterable(events), "public").collectList().block();
        var initial = read(wire.getFirst().data()).path("message").path("usage");
        assertThat(initial.isObject()).isTrue();
        assertThat(initial.get("input_tokens").isNull()).isTrue();
        assertThat(initial.get("output_tokens").isNull()).isTrue();
        var complete = read(wire.get(wire.size() - 2).data()).path("usage");
        assertThat(complete.isObject()).isTrue();
        assertThat(complete).isEqualTo(read(object().put("input_tokens", usage == null ? null : usage.inputTokens())
                .put("output_tokens", usage == null ? null : usage.outputTokens()).toString()));
    }

    static java.util.stream.Stream<Usage> usageSamples() {
        return java.util.stream.Stream.of(null, new Usage(4L, 2L, 6L), new Usage(null, 2L, null), new Usage(4L, null, null));
    }

    private com.fasterxml.jackson.databind.JsonNode tool(int index, String id, String name, String arguments) {
        var function = object().put("arguments", arguments);
        if (name != null) function.put("name", name);
        var tool = object().put("index", index).set("function", function);
        if (id != null) ((com.fasterxml.jackson.databind.node.ObjectNode) tool).put("id", id);
        return tool;
    }
    private String chunk(com.fasterxml.jackson.databind.JsonNode delta, String finish) {
        var choice = object().put("index", 0).put("finish_reason", finish); choice.set("delta", delta);
        return object().put("id", "s").set("choices", MAPPER.createArrayNode().add(choice)).toString();
    }
}
