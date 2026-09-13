package com.llmgateway.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRequestValidationTest {
    private static final Message USER = message(MessageRole.USER, ContentBlock.text("fixture prompt"));

    @Test
    void preservesUnspecifiedParametersAndDefaultsToolChoiceFromAvailableTools() {
        var request = request(List.of(USER), null, null);

        assertThat(request.messages()).containsExactly(USER);
        assertThat(request.tools()).isEmpty();
        assertThat(request.toolChoice()).isEqualTo(ToolChoice.Mode.NONE);
        assertThat(request.reasoning()).isNull();
        assertThat(request.generation()).isNull();
        assertThat(request.responseFormat()).isNull();
        assertThat(request.stream()).isFalse();
        assertThat(request(List.of(USER), List.of(tool("lookup")), null).toolChoice()).isEqualTo(ToolChoice.Mode.AUTO);
    }

    @Test
    void keepsMessagesContentAndToolsImmutable() {
        var content = new ArrayList<>(List.of(ContentBlock.text("  preserved\n")));
        var message = new Message(MessageRole.USER, content);
        var messages = new ArrayList<>(List.of(message));
        var tools = new ArrayList<>(List.of(tool("lookup")));
        var request = request(messages, tools, null);
        content.clear();
        messages.clear();
        tools.clear();

        assertThat(request.messages()).containsExactly(message);
        assertThat(request.messages().getFirst().content()).containsExactly(ContentBlock.text("  preserved\n"));
        assertThat(request.tools()).hasSize(1);
        assertThatThrownBy(() -> request.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> message.content().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresANonblankLogicalModelName(String model) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmRequest(model, List.of(USER), null, null, null, null, false));
    }

    @Test
    void boundsModelNamesWithoutTrimmingOrRewritingThem() {
        String model = "m".repeat(LlmInputLimits.MAX_MODEL_NAME_LENGTH);
        assertThat(new LlmRequest(model, List.of(USER), null, null, null, null, false).model()).isEqualTo(model);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmRequest(model + "m", List.of(USER), null, null, null, null, false));
        assertThat(new LlmRequest(" model ", List.of(USER), null, null, null, null, false).model()).isEqualTo(" model ");
    }

    @Test
    void rejectsMissingEmptyAndNullContainingMessages() {
        assertThatIllegalArgumentException().isThrownBy(() -> request(null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(), null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> request(Arrays.asList(USER, null), null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), Arrays.asList(tool("lookup"), null), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Message(null, USER.content()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Message(MessageRole.USER, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Message(MessageRole.USER, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Message(MessageRole.USER, Arrays.asList(ContentBlock.text("fixture"), null)));
    }

    @ParameterizedTest
    @EnumSource(value = MessageRole.class, names = {"SYSTEM", "DEVELOPER", "USER", "ASSISTANT"})
    void preservesEmptyTextWithoutTreatingItAsAMissingMessage(MessageRole role) {
        assertThat(message(role, ContentBlock.text("")).content()).containsExactly(ContentBlock.text(""));
    }

    @ParameterizedTest
    @EnumSource(value = MessageRole.class, names = {"SYSTEM", "DEVELOPER", "USER", "TOOL"})
    void restrictsToolCallsAndThinkingToAssistantMessages(MessageRole role) {
        assertThatIllegalArgumentException().isThrownBy(() -> message(role, call("call-1")));
        assertThatIllegalArgumentException().isThrownBy(() -> message(role, new ContentBlock.Thinking("fixture")));
    }

    @ParameterizedTest
    @EnumSource(value = MessageRole.class, names = {"SYSTEM", "DEVELOPER", "USER", "ASSISTANT"})
    void requiresToolResultsToBeNormalizedIntoToolMessages(MessageRole role) {
        assertThatIllegalArgumentException().isThrownBy(() -> message(role, result("call-1")));
    }

    @Test
    void rejectsOrdinaryContentMixedIntoAToolMessage() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                message(MessageRole.TOOL, result("call-1"), ContentBlock.text("fixture")));
    }

    @Test
    void acceptsParallelResultsInEitherOrderAndAcrossConsecutiveToolMessages() {
        var calls = message(MessageRole.ASSISTANT, new ContentBlock.Thinking("fixture reasoning"),
                call("call-1"), call("call-2"));
        var second = new ToolResult("call-2", List.of(), true);
        var messages = List.of(USER, calls, message(MessageRole.TOOL, second),
                message(MessageRole.TOOL, result("call-1")), USER,
                message(MessageRole.ASSISTANT, call("call-3")), message(MessageRole.TOOL, result("call-3")));

        assertThat(request(messages, List.of(), ToolChoice.Mode.NONE).messages()).containsExactlyElementsOf(messages);
        assertThat(request(List.of(USER, calls, message(MessageRole.TOOL, second, result("call-1"))), null, null)
                .messages()).hasSize(3);
    }

    @ParameterizedTest
    @MethodSource("invalidToolHistories")
    void rejectsOrphanDuplicateReusedAndUnresolvedToolCallIds(List<Message> messages) {
        assertThatIllegalArgumentException().isThrownBy(() -> request(messages, null, null));
    }

    static Stream<List<Message>> invalidToolHistories() {
        var calls = message(MessageRole.ASSISTANT, call("call-1"), call("call-2"));
        var first = message(MessageRole.TOOL, result("call-1"));
        return Stream.of(
                List.of(first),
                List.of(first, message(MessageRole.ASSISTANT, call("call-1"))),
                List.of(message(MessageRole.ASSISTANT, call("call-1"))),
                List.of(calls, first),
                List.of(calls, first, USER, message(MessageRole.TOOL, result("call-2"))),
                List.of(calls, first, message(MessageRole.ASSISTANT, ContentBlock.text("too early"))),
                List.of(calls, message(MessageRole.TOOL, result("unknown"))),
                List.of(calls, first, first),
                List.of(message(MessageRole.ASSISTANT, call("call-1"), call("call-1")), first),
                List.of(message(MessageRole.ASSISTANT, call("call-1")), first, first),
                List.of(message(MessageRole.ASSISTANT, call("call-1")), first,
                        message(MessageRole.ASSISTANT, call("call-1")), first));
    }

    @ParameterizedTest
    @EnumSource(ToolChoice.Mode.class)
    void acceptsEachToolChoiceModeWithDeclaredTools(ToolChoice.Mode choice) {
        assertThat(request(List.of(USER), List.of(tool("lookup")), choice).toolChoice()).isEqualTo(choice);
    }

    @Test
    void requiresUniqueToolNamesAndResolvesForcedChoicesCaseSensitively() {
        var lookup = tool("lookup");
        var named = new ToolChoice.Named("lookup");
        assertThat(request(List.of(USER), List.of(lookup), named).toolChoice()).isEqualTo(named);
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), List.of(lookup, lookup), null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                request(List.of(USER), List.of(lookup), new ToolChoice.Named("Lookup")));
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), null, named));
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), null, ToolChoice.Mode.REQUIRED));
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), null, ToolChoice.Mode.AUTO));
    }

    @Test
    void validatesReasoningBudgetAgainstTheOutputTokenLimit() {
        var generation = new GenerationConfig(null, null, 100, null);
        var valid = new LlmRequest("model", List.of(USER), null, new ReasoningConfig(true, 99),
                generation, null, false);

        assertThat(valid.reasoning().budgetTokens()).isEqualTo(99);
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmRequest("model", List.of(USER), null,
                new ReasoningConfig(true, 100), generation, null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmRequest("model", List.of(USER), null,
                new ReasoningConfig(true, 101), generation, null, false));
        assertThat(new LlmRequest("model", List.of(USER), null, new ReasoningConfig(true, null),
                generation, null, true).reasoning().budgetTokens()).isNull();
        assertThat(new LlmRequest("model", List.of(USER), null, new ReasoningConfig(true, 100),
                null, null, false).generation()).isNull();
    }

    @Test
    void boundsMessageToolAndPerMessageBlockCounts() {
        assertThat(request(Collections.nCopies(LlmInputLimits.MAX_MESSAGES, USER), null, null).messages())
                .hasSize(LlmInputLimits.MAX_MESSAGES);
        assertThatIllegalArgumentException().isThrownBy(() ->
                request(Collections.nCopies(LlmInputLimits.MAX_MESSAGES + 1, USER), null, null));
        var tools = IntStream.range(0, LlmInputLimits.MAX_TOOLS).mapToObj(i -> tool("tool_" + i)).toList();
        assertThat(request(List.of(USER), tools, null).tools()).hasSize(LlmInputLimits.MAX_TOOLS);
        var tooManyTools = new ArrayList<>(tools);
        tooManyTools.add(tool("extra"));
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), tooManyTools, null));
        var blocks = Collections.nCopies(LlmInputLimits.MAX_BLOCKS_PER_MESSAGE, ContentBlock.text(""));
        assertThat(new Message(MessageRole.USER, blocks).content()).hasSize(LlmInputLimits.MAX_BLOCKS_PER_MESSAGE);
        assertThat(new ToolResult("call-1", blocks, false).content()).hasSize(LlmInputLimits.MAX_BLOCKS_PER_MESSAGE);
        var tooManyBlocks = Collections.nCopies(LlmInputLimits.MAX_BLOCKS_PER_MESSAGE + 1, ContentBlock.text(""));
        assertThatIllegalArgumentException().isThrownBy(() -> new Message(MessageRole.USER, tooManyBlocks));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolResult("call-1", tooManyBlocks, false));
    }

    @Test
    void boundsTotalBlocksIncludingNestedToolResultContent() {
        var full = new Message(MessageRole.USER,
                Collections.nCopies(LlmInputLimits.MAX_BLOCKS_PER_MESSAGE, ContentBlock.text("")));
        var messages = new ArrayList<>(Collections.nCopies(
                LlmInputLimits.MAX_CONTENT_BLOCKS / LlmInputLimits.MAX_BLOCKS_PER_MESSAGE, full));
        assertThat(request(messages, null, null).messages()).hasSize(16);
        messages.add(USER);
        assertThatIllegalArgumentException().isThrownBy(() -> request(messages, null, null));

        var toolMessages = new ArrayList<Message>();
        for (int i = 0; i < 16; i++) {
            toolMessages.add(message(MessageRole.ASSISTANT, call("call-" + i)));
            toolMessages.add(message(MessageRole.TOOL, new ToolResult("call-" + i, full.content(), false)));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> request(toolMessages, null, null))
                .withMessage("request content exceeds maximum block count");
    }

    @Test
    void countsAggregateTextPayloadAndAcceptsTheExactLimit() {
        var atLimit = message(MessageRole.USER, ContentBlock.text("x".repeat(LlmInputLimits.MAX_PAYLOAD_CHARACTERS - 5)));
        assertThat(request(List.of(atLimit), null, null).model()).isEqualTo("model");
        assertThatIllegalArgumentException().isThrownBy(() ->
                request(List.of(atLimit, message(MessageRole.USER, ContentBlock.text("x"))), null, null))
                .withMessage("request payload exceeds maximum character count");
    }

    @ParameterizedTest
    @MethodSource("largeContent")
    void countsThinkingMediaAndNestedToolResultsTowardTheInputBudget(ContentBlock block) {
        List<Message> history;
        if (block instanceof ToolResult result) {
            history = List.of(message(MessageRole.ASSISTANT, call(result.toolCallId())),
                    message(MessageRole.TOOL, result));
        } else {
            history = List.of(message(MessageRole.ASSISTANT, block));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> request(history, null, null))
                .withMessage("request payload exceeds maximum character count");
    }

    static Stream<ContentBlock> largeContent() {
        String large = "a".repeat(LlmInputLimits.MAX_PAYLOAD_CHARACTERS);
        var inline = new MediaSource.InlineData(large, "image/png");
        return Stream.of(new ContentBlock.Thinking(large), new ContentBlock.Image(inline),
                new ContentBlock.Document(new MediaSource.Url(URI.create("https://fixture.invalid/" + large), null)),
                new ToolResult("call-1", List.of(ContentBlock.text(large)), false));
    }

    @Test
    void countsToolAndResponseSchemasDescriptionsAndGenerationOptionsInTheAggregateBudget() {
        String chunk = "x".repeat(LlmInputLimits.MAX_JSON_CHARACTERS / 2);
        var schema = JsonNodeFactory.instance.objectNode().put("description", chunk);
        var tools = IntStream.range(0, 16).mapToObj(i -> new ToolDefinition("tool_" + i, null, schema)).toList();
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), tools, null))
                .withMessage("request payload exceeds maximum character count");
        var largeDescription = new ToolDefinition("lookup", "x".repeat(LlmInputLimits.MAX_PAYLOAD_CHARACTERS),
                JsonNodeFactory.instance.objectNode());
        assertThatIllegalArgumentException().isThrownBy(() -> request(List.of(USER), List.of(largeDescription), null));
        var almostFull = message(MessageRole.USER,
                ContentBlock.text("x".repeat(LlmInputLimits.MAX_PAYLOAD_CHARACTERS - 5)));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmRequest("model", List.of(almostFull), null,
                null, null, new ResponseFormat.JsonSchema("answer", null, schema, true), false));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmRequest("model", List.of(almostFull), null,
                null, new GenerationConfig(null, null, null, null, List.of("stop")), null, false));
    }

    @Test
    void validationErrorsDoNotExposeToolIdsNamesArgumentsOrPrompts() {
        var call = new ToolCall("private-call-id", "private-tool", JsonNodeFactory.instance.objectNode()
                .put("secret", "private-arguments"));
        var messages = List.of(message(MessageRole.USER, ContentBlock.text("private-prompt")),
                message(MessageRole.ASSISTANT, call), USER);

        assertThatIllegalArgumentException().isThrownBy(() -> request(messages, null, null))
                .withMessage("tool calls require results before the next non-tool message");
        assertThatIllegalArgumentException().isThrownBy(() ->
                request(List.of(USER), List.of(tool("lookup")), new ToolChoice.Named("private-tool")))
                .withMessage("named tool choice must reference a declared tool");
    }

    private static LlmRequest request(List<Message> messages, List<ToolDefinition> tools, ToolChoice choice) {
        return new LlmRequest("model", messages, tools, choice, null, null, null, false);
    }

    private static Message message(MessageRole role, ContentBlock... blocks) {
        return new Message(role, List.of(blocks));
    }

    private static ToolCall call(String id) {
        return new ToolCall(id, "lookup", JsonNodeFactory.instance.objectNode());
    }

    private static ToolResult result(String id) {
        return new ToolResult(id, List.of(ContentBlock.text("fixture result")), false);
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, null, JsonNodeFactory.instance.objectNode().put("type", "object"));
    }
}
