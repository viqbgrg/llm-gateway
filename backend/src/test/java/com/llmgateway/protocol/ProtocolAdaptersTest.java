package com.llmgateway.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmgateway.inference.GatewayException;
import com.llmgateway.model.*;
import com.llmgateway.protocol.chat.*;
import com.llmgateway.protocol.anthropic.*;
import com.llmgateway.protocol.responses.*;
import com.llmgateway.provider.chat.ChatProviderAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.llmgateway.protocol.ProtocolJson.*;

class ProtocolAdaptersTest {
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final ChatCompletionsAdapter chat = new ChatCompletionsAdapter(clock);
    private final AnthropicAdapter anthropic = new AnthropicAdapter();
    private final ResponsesAdapter responses = new ResponsesAdapter(clock);
    @ParameterizedTest @EnumSource(Protocol.class)
    void normalizesFixturesAndEncodesTheProtocolResponse(Protocol protocol) throws Exception {
        JsonNode caseDefinition = read(fixture("manifest.json")).get("cases").get(protocol.ordinal());
        LlmRequest request = parse(protocol, fixture(caseDefinition.get("request").asText()));
        assertThat((JsonNode) MAPPER.valueToTree(request)).isEqualTo(read(fixture("text-ir.json")));
        var provider = new ChatProviderAdapter(null, null, null, clock);
        var response = provider.decode(fixture("chat-response.json"), request.model());
        JsonNode encoded = MAPPER.valueToTree(switch (protocol) { case CHAT_COMPLETIONS -> chat.encode(response); case ANTHROPIC -> anthropic.encode(response); case RESPONSES -> responses.encode(response); });
        assertThat(read(encoded.toString())).isEqualTo(read(fixture(caseDefinition.get("clientResponse").asText())));
        assertThat(ChatCompletionsAdapter.outbound(request, "real-model").model()).isEqualTo("real-model");
        assertThat(request.model()).isEqualTo("public-model");
    }
    @ParameterizedTest @EnumSource(Protocol.class)
    void preservesTheSameToolAndImageConversationAcrossAllProtocols(Protocol protocol) throws Exception {
        JsonNode example = read(fixture("manifest.json")).get("conversationCases").get(protocol.ordinal());
        LlmRequest request = parse(protocol, fixture(example.get("request").asText()));
        assertThat(request).isEqualTo(parse(Protocol.CHAT_COMPLETIONS, fixture("chat-conversation-request.json")));
        assertThat((JsonNode) MAPPER.valueToTree(ChatCompletionsAdapter.outbound(request, "real-model")))
                .isEqualTo(read(fixture(example.get("upstreamRequest").asText())));
        var response = new ChatProviderAdapter(null, null, null, clock)
                .decode(fixture(example.get("upstreamResponse").asText()), request.model());
        JsonNode encoded = MAPPER.valueToTree(switch (protocol) {
            case CHAT_COMPLETIONS -> chat.encode(response);
            case ANTHROPIC -> anthropic.encode(response);
            case RESPONSES -> responses.encode(response);
        });
        assertThat(read(encoded.toString())).isEqualTo(read(fixture(example.get("clientResponse").asText())));
    }
    @Test void rejectsEveryNegativeFixtureBeforeItCanReachAProvider() throws Exception {
        for (JsonNode example : read(fixture("manifest.json")).get("rejections")) {
            assertThatThrownBy(() -> parse(Protocol.valueOf(example.get("protocol").asText()), example.get("request").toString())).isInstanceOf(Exception.class);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"\"logprobs\":true", "\"parallel_tool_calls\":false", "\"reasoning_effort\":\"high\"", "\"max_completion_tokens\":10"})
    void rejectsUnknownChatParameters(String field) {
        assertThatThrownBy(() -> parse(Protocol.CHAT_COMPLETIONS, "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]," + field + "}"))
                .isInstanceOf(Exception.class);
    }
    @Test void preservesToolIdsArgumentsResultsAndImageSources() throws Exception {
        var request = parse(Protocol.CHAT_COMPLETIONS, """
                {"model":"public","messages":[
                  {"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,YQ=="}},{"type":"text","text":"read"}]},
                  {"role":"assistant","content":null,"tool_calls":[{"id":"call_a","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"call_a","content":"result"}],
                 "tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}],
                 "tool_choice":{"type":"function","function":{"name":"lookup"}}}
                """);
        var wire = ChatCompletionsAdapter.outbound(request, "actual");
        assertThat(wire.messages().get(1).toolCalls().getFirst().id()).isEqualTo("call_a");
        assertThat(wire.messages().get(2).toolCallId()).isEqualTo("call_a");
        assertThat(wire.messages().getFirst().content().get(0).get("image_url").get("url").asText()).isEqualTo("data:image/png;base64,YQ==");
    }
    @Test void mapsParallelAnthropicToolResultsAndResponsesCallsWithoutLosingAssociation() throws Exception {
        var a = parse(Protocol.ANTHROPIC, """
                {"model":"m","max_tokens":50,"messages":[
                  {"role":"assistant","content":[{"type":"tool_use","id":"c1","name":"f","input":{}},{"type":"tool_use","id":"c2","name":"f","input":{}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"c2","content":"2"},{"type":"tool_result","tool_use_id":"c1","content":"1"},{"type":"text","text":"continue"}]}]}
                """);
        assertThat(a.messages()).extracting(Message::role).containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.USER);
        var r = parse(Protocol.RESPONSES, """
                {"model":"m","input":[{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"},
                  {"type":"function_call","call_id":"c2","name":"f","arguments":"{}"},
                  {"type":"function_call_output","call_id":"c2","output":"2"},{"type":"function_call_output","call_id":"c1","output":"1"}]}
                """);
        assertThat(r.messages().getFirst().content()).hasSize(2);
    }
    @Test void retainsUnknownUsageAndRejectsUnsupportedImageDetailAndToolErrors() throws Exception {
        LlmResponse response = new LlmResponse("r", "m", List.of(ContentBlock.text("x")), FinishReason.STOP, null);
        assertThat(chat.encode(response).usage()).isNull();
        assertThat(responses.encode(response).usage()).isNull();
        assertThat(anthropic.encode(response).usage()).isNull();
        assertThatThrownBy(() -> parse(Protocol.CHAT_COMPLETIONS, """
                {"model":"m","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.invalid/i","detail":"high"}}]}]}
                """)).isInstanceOf(GatewayException.class);
    }
    private LlmRequest parse(Protocol protocol, String json) throws Exception {
        RequestContext context = new RequestContext("fixture", protocol, Instant.EPOCH, 0);
        return switch (protocol) {
            case CHAT_COMPLETIONS -> chat.parse(MAPPER.readValue(json, ChatRequest.class), context);
            case ANTHROPIC -> anthropic.parse(MAPPER.readValue(json, AnthropicRequest.class), context);
            case RESPONSES -> responses.parse(MAPPER.readValue(json, ResponsesRequest.class), context);
        };
    }
    public static String fixture(String name) throws Exception {
        try (var input = ProtocolAdaptersTest.class.getResourceAsStream("/fixtures/" + name)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }
}
