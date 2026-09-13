package com.llmgateway.model;

import com.llmgateway.model.LlmStreamEvent.ContentBlockEnd;
import com.llmgateway.model.LlmStreamEvent.ContentBlockStart;
import com.llmgateway.model.LlmStreamEvent.Error;
import com.llmgateway.model.LlmStreamEvent.MessageEnd;
import com.llmgateway.model.LlmStreamEvent.MessageStart;
import com.llmgateway.model.LlmStreamEvent.TextDelta;
import com.llmgateway.model.LlmStreamEvent.ThinkingDelta;
import com.llmgateway.model.LlmStreamEvent.ToolCallDelta;
import com.llmgateway.model.LlmStreamEvent.ToolCallEnd;
import com.llmgateway.model.LlmStreamEvent.ToolCallStart;
import com.llmgateway.model.LlmStreamEvent.UsageUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LlmStreamEventContractTest {
    private static final String MESSAGE_ID = "message-1";

    @Test
    void representsEveryEventWithItsOwnTypedPayload() {
        assertThat(events(MESSAGE_ID)).extracting(LlmStreamEvent::type).containsExactly(
                LlmStreamEventType.MESSAGE_START, LlmStreamEventType.CONTENT_BLOCK_START,
                LlmStreamEventType.TEXT_DELTA, LlmStreamEventType.THINKING_DELTA,
                LlmStreamEventType.CONTENT_BLOCK_END, LlmStreamEventType.TOOL_CALL_START,
                LlmStreamEventType.TOOL_CALL_DELTA, LlmStreamEventType.TOOL_CALL_END,
                LlmStreamEventType.USAGE, LlmStreamEventType.MESSAGE_END, LlmStreamEventType.ERROR);
        var call = new ToolCallStart(MESSAGE_ID, 2, "call-1", "lookup");
        assertThat(call.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(call.contentBlockIndex()).isEqualTo(2);
        assertThat(call.toolCallId()).isEqualTo("call-1");
        assertThat(call.toolName()).isEqualTo("lookup");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresMessageAndToolIdentityWithoutTrimming(String value) {
        for (var constructor : messageEventConstructors()) {
            assertThatIllegalArgumentException().isThrownBy(() -> constructor.apply(value));
        }
        if (value != null) {
            assertThatIllegalArgumentException().isThrownBy(() -> new Error(value, LlmStreamError.UPSTREAM_ERROR));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallStart(MESSAGE_ID, 0, value, "lookup"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallDelta(MESSAGE_ID, 0, value, "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallEnd(MESSAGE_ID, 0, value));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallStart(MESSAGE_ID, 0, "call-1", value));
    }

    @Test
    void boundsIdentifiersAndNamesAndPreservesAllowedValuesExactly() {
        String longestId = "i".repeat(LlmInputLimits.MAX_IDENTIFIER_LENGTH);
        String longestName = "n".repeat(LlmInputLimits.MAX_TOOL_NAME_LENGTH);
        for (var constructor : messageEventConstructors()) {
            assertThat(constructor.apply(longestId).messageId()).isEqualTo(longestId);
            assertThatIllegalArgumentException().isThrownBy(() -> constructor.apply(longestId + "i"));
        }
        assertThat(new Error(longestId, LlmStreamError.TIMEOUT).messageId()).isEqualTo(longestId);
        assertThatIllegalArgumentException().isThrownBy(() -> new Error(longestId + "i", LlmStreamError.TIMEOUT));
        assertThat(new ToolCallStart(MESSAGE_ID, 0, longestId, longestName).toolName()).isEqualTo(longestName);
        assertThat(new MessageStart(" message ").messageId()).isEqualTo(" message ");
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallStart(MESSAGE_ID, 0, longestId + "i", "lookup"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallDelta(MESSAGE_ID, 0, longestId + "i", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallEnd(MESSAGE_ID, 0, longestId + "i"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallStart(MESSAGE_ID, 0, "call-1", longestName + "n"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, LlmInputLimits.MAX_CONTENT_BLOCKS, Integer.MAX_VALUE})
    void rejectsOutOfRangeContentIndices(int index) {
        for (var constructor : blockEventConstructors()) {
            assertThatIllegalArgumentException().isThrownBy(() -> constructor.apply(index));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ContentBlockType.class, names = {"TEXT", "THINKING"}, mode = EnumSource.Mode.EXCLUDE)
    void requiresTextOrThinkingForGenericBlockStarts(ContentBlockType type) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlockStart(MESSAGE_ID, 0, type));
    }

    @Test
    void rejectsMissingPayloads() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ContentBlockStart(MESSAGE_ID, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new TextDelta(MESSAGE_ID, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ThinkingDelta(MESSAGE_ID, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallDelta(MESSAGE_ID, 0, "call-1", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new UsageUpdate(MESSAGE_ID, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new MessageEnd(MESSAGE_ID, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Error(MESSAGE_ID, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmStreamEventValidator().accept(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "文字", "{\"city\":\"\\u"})
    void preservesEmptyWhitespaceAndIncompleteFragments(String fragment) {
        assertThat(new TextDelta(MESSAGE_ID, 0, fragment).text()).isEqualTo(fragment);
        assertThat(new ThinkingDelta(MESSAGE_ID, 0, fragment).text()).isEqualTo(fragment);
        assertThat(new ToolCallDelta(MESSAGE_ID, 0, "call-1", fragment).argumentsDelta()).isEqualTo(fragment);
    }

    @Test
    void boundsIndividualDeltaSizes() {
        String longestText = "x".repeat(LlmInputLimits.MAX_PAYLOAD_CHARACTERS);
        assertThat(new TextDelta(MESSAGE_ID, 0, longestText).text()).hasSize(longestText.length());
        assertThat(new ThinkingDelta(MESSAGE_ID, 0, longestText).text()).hasSize(longestText.length());
        String tooLongText = longestText + "x";
        assertThatIllegalArgumentException().isThrownBy(() -> new TextDelta(MESSAGE_ID, 0, tooLongText));
        assertThatIllegalArgumentException().isThrownBy(() -> new ThinkingDelta(MESSAGE_ID, 0, tooLongText));
        String tooLongJson = " ".repeat(LlmInputLimits.MAX_JSON_CHARACTERS + 1);
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCallDelta(MESSAGE_ID, 0, "call-1", tooLongJson));
    }

    @ParameterizedTest
    @EnumSource(value = FinishReason.class, names = "TOOL_CALLS", mode = EnumSource.Mode.EXCLUDE)
    void acceptsEmptyMessagesWithAnExplicitFinishReasonAndNoUsage(FinishReason reason) {
        var end = new MessageEnd(MESSAGE_ID, reason);
        validate(new MessageStart(MESSAGE_ID), end);
        assertThat(end.finishReason()).isEqualTo(reason);
    }

    @Test
    void acceptsEmptyTextAndThinkingBlocks() {
        validate(new MessageStart(MESSAGE_ID),
                new ContentBlockStart(MESSAGE_ID, 0, ContentBlockType.THINKING),
                new ContentBlockEnd(MESSAGE_ID, 0),
                new ContentBlockStart(MESSAGE_ID, 1, ContentBlockType.TEXT),
                new TextDelta(MESSAGE_ID, 1, ""),
                new ContentBlockEnd(MESSAGE_ID, 1),
                new MessageEnd(MESSAGE_ID, FinishReason.STOP));
    }

    @Test
    void associatesInterleavedToolsAndContentWhileJsonTokensAreIncomplete() {
        validate(new MessageStart(MESSAGE_ID),
                new ContentBlockStart(MESSAGE_ID, 0, ContentBlockType.THINKING),
                new ThinkingDelta(MESSAGE_ID, 0, "fixture reasoning"),
                new ToolCallStart(MESSAGE_ID, 1, "call-A", "lookup"),
                new ToolCallDelta(MESSAGE_ID, 1, "call-A", "{\"city\":\"\\u"),
                new ToolCallStart(MESSAGE_ID, 2, "call-a", "lookup"),
                new ToolCallDelta(MESSAGE_ID, 2, "call-a", "{\"values\":["),
                new ContentBlockStart(MESSAGE_ID, 3, ContentBlockType.TEXT),
                new TextDelta(MESSAGE_ID, 3, "fixture output"),
                new ToolCallDelta(MESSAGE_ID, 1, "call-A", "676d\\u5dde"),
                new ToolCallDelta(MESSAGE_ID, 2, "call-a", "1,2]}"),
                new ToolCallEnd(MESSAGE_ID, 2, "call-a"),
                new ToolCallDelta(MESSAGE_ID, 1, "call-A", "\"}"),
                new ContentBlockEnd(MESSAGE_ID, 3),
                new ToolCallEnd(MESSAGE_ID, 1, "call-A"),
                new ContentBlockEnd(MESSAGE_ID, 0),
                new UsageUpdate(MESSAGE_ID, new Usage(4, 2, 6)),
                new MessageEnd(MESSAGE_ID, FinishReason.TOOL_CALLS));
    }

    @Test
    void preservesUnknownAndPartialUsageSnapshotsWithoutInventingOrAddingCounters() {
        var unknown = new UsageUpdate(MESSAGE_ID, new Usage(null, null, null));
        var initial = new UsageUpdate(MESSAGE_ID, new Usage(4L, null, null));
        var finalUsage = new UsageUpdate(MESSAGE_ID, new Usage(4L, 0L, null));
        validate(new MessageStart(MESSAGE_ID), unknown, initial,
                new ContentBlockStart(MESSAGE_ID, 0, ContentBlockType.TEXT),
                new TextDelta(MESSAGE_ID, 0, ""), finalUsage,
                new ContentBlockEnd(MESSAGE_ID, 0), new MessageEnd(MESSAGE_ID, FinishReason.STOP));
        assertThat(unknown.usage().inputTokens()).isNull();
        assertThat(initial.usage().outputTokens()).isNull();
        assertThat(finalUsage.usage().inputTokens()).isEqualTo(4);
        assertThat(finalUsage.usage().outputTokens()).isZero();
        assertThat(finalUsage.usage().totalTokens()).isNull();
    }

    @ParameterizedTest
    @EnumSource(LlmStreamError.class)
    void allowsSafeErrorsBeforeStartOrDuringUnfinishedToolArguments(LlmStreamError error) {
        validate(new Error(null, error));
        validate(new Error(MESSAGE_ID, error));
        validate(new MessageStart(MESSAGE_ID), new ToolCallStart(MESSAGE_ID, 0, "call-1", "lookup"),
                new ToolCallDelta(MESSAGE_ID, 0, "call-1", "{\"unfinished\":"), new Error(MESSAGE_ID, error));
        assertThat(error.code()).matches("[a-z_]+");
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void requiresTheActiveMessageIdEvenForErrors() {
        var validator = started();
        assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(new Error(null, LlmStreamError.TIMEOUT)));
    }

    @ParameterizedTest
    @MethodSource("nonInitialEvents")
    void rejectsEventsBeforeTheMessageStarts(LlmStreamEvent event) {
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmStreamEventValidator().accept(event));
    }

    static Stream<LlmStreamEvent> nonInitialEvents() {
        return events(MESSAGE_ID).stream()
                .filter(event -> !(event instanceof MessageStart) && !(event instanceof Error));
    }

    @ParameterizedTest
    @MethodSource("wrongMessageEvents")
    void rejectsEventsForAnotherMessage(LlmStreamEvent event) {
        var validator = started();
        assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(event))
                .withMessage("stream event must reference the active message");
    }

    static Stream<LlmStreamEvent> wrongMessageEvents() {
        return events("other-message").stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSequences")
    void rejectsIllegalContentAndToolSequences(String scenario, List<LlmStreamEvent> sequence) {
        var validator = started();
        sequence.subList(0, sequence.size() - 1).forEach(validator::accept);
        assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(sequence.getLast()));
    }

    static Stream<Arguments> invalidSequences() {
        var text = new ContentBlockStart(MESSAGE_ID, 0, ContentBlockType.TEXT);
        var thinking = new ContentBlockStart(MESSAGE_ID, 0, ContentBlockType.THINKING);
        var textEnd = new ContentBlockEnd(MESSAGE_ID, 0);
        var textDelta = new TextDelta(MESSAGE_ID, 0, "fixture");
        var thinkingDelta = new ThinkingDelta(MESSAGE_ID, 0, "fixture");
        var call = new ToolCallStart(MESSAGE_ID, 0, "call-1", "lookup");
        var arguments = new ToolCallDelta(MESSAGE_ID, 0, "call-1", "{}");
        var callEnd = new ToolCallEnd(MESSAGE_ID, 0, "call-1");
        return Stream.of(
                Arguments.of("duplicate message start", List.of(new MessageStart(MESSAGE_ID))),
                Arguments.of("text before block start", List.of(textDelta)),
                Arguments.of("thinking before block start", List.of(thinkingDelta)),
                Arguments.of("tool delta before call start", List.of(arguments)),
                Arguments.of("tool end before call start", List.of(callEnd)),
                Arguments.of("text end before block start", List.of(textEnd)),
                Arguments.of("skipped first index", List.of(new ContentBlockStart(MESSAGE_ID, 1, ContentBlockType.TEXT))),
                Arguments.of("skipped later index", List.of(text, new ToolCallStart(MESSAGE_ID, 2, "call-1", "lookup"))),
                Arguments.of("reused open index", List.of(text, thinking)),
                Arguments.of("reused closed index", List.of(text, textEnd, call)),
                Arguments.of("thinking in text block", List.of(text, thinkingDelta)),
                Arguments.of("text in thinking block", List.of(thinking, textDelta)),
                Arguments.of("text in tool block", List.of(call, textDelta)),
                Arguments.of("tool delta in text block", List.of(text, arguments)),
                Arguments.of("generic end for tool block", List.of(call, textEnd)),
                Arguments.of("tool end for text block", List.of(text, callEnd)),
                Arguments.of("text after block end", List.of(text, textEnd, textDelta)),
                Arguments.of("duplicate content end", List.of(text, textEnd, textEnd)),
                Arguments.of("wrong tool ID in delta", List.of(call, new ToolCallDelta(MESSAGE_ID, 0, "other-call", "{}"))),
                Arguments.of("wrong tool ID in end", List.of(call, arguments, new ToolCallEnd(MESSAGE_ID, 0, "other-call"))),
                Arguments.of("missing tool arguments", List.of(call, callEnd)),
                Arguments.of("delta after tool end", List.of(call, arguments, callEnd, arguments)),
                Arguments.of("duplicate tool end", List.of(call, arguments, callEnd, callEnd)),
                Arguments.of("reused open call ID", List.of(call, new ToolCallStart(MESSAGE_ID, 1, "call-1", "lookup"))),
                Arguments.of("reused closed call ID", List.of(call, arguments, callEnd,
                        new ToolCallStart(MESSAGE_ID, 1, "call-1", "lookup"))),
                Arguments.of("open text at message end", List.of(text, new MessageEnd(MESSAGE_ID, FinishReason.STOP))),
                Arguments.of("open thinking at length limit", List.of(thinking, new MessageEnd(MESSAGE_ID, FinishReason.LENGTH))),
                Arguments.of("open tool at length limit", List.of(call,
                        new ToolCallDelta(MESSAGE_ID, 0, "call-1", "{"), new MessageEnd(MESSAGE_ID, FinishReason.LENGTH))),
                Arguments.of("tool finish without tools", List.of(new MessageEnd(MESSAGE_ID, FinishReason.TOOL_CALLS))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "{\"value\":", "[]", "null", "42", "true", "\"text\"",
            "{} {}", "{} trailing", "{\"x\":1,}", "{\"x\":NaN}", "{\"x\":1,\"x\":2}",
            "{\"nested\":{\"x\":1,\"x\":2}}", "{\"x\":\"\\u12\"}"})
    void rejectsIncompleteMalformedOrAmbiguousJsonWhenTheCallEnds(String arguments) {
        var validator = toolWithArguments(arguments);
        assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(new ToolCallEnd(MESSAGE_ID, 0, "call-1")))
                .withNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", " \n { \"a\": [null,true,false,1,-2.5e2,{},[]], \"b\":\"文字\" } \t",
            "{\"first\":{\"x\":1},\"second\":{\"x\":2}}"})
    void acceptsOneCompleteObjectWithNestedJsonValues(String arguments) {
        finishTool(toolWithArguments(arguments));
    }

    @Test
    void validatesJsonDepthAndNodeCountAtTheirExactLimits() {
        finishTool(toolWithArguments(nestedArguments(LlmInputLimits.MAX_JSON_DEPTH)));
        finishTool(toolWithArguments(arrayArguments(LlmInputLimits.MAX_JSON_NODES - 2)));
        for (String oversized : List.of(nestedArguments(LlmInputLimits.MAX_JSON_DEPTH + 1),
                arrayArguments(LlmInputLimits.MAX_JSON_NODES - 1))) {
            var validator = toolWithArguments(oversized);
            assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(new ToolCallEnd(MESSAGE_ID, 0, "call-1")));
        }
    }

    @Test
    void boundsTheCombinedArgumentsAcrossFragments() {
        String maximum = maximumArguments();
        finishTool(toolWithArguments(maximum));
        var validator = toolWithArguments(maximum);
        validator.accept(new ToolCallDelta(MESSAGE_ID, 0, "call-1", ""));
        assertThatIllegalArgumentException().isThrownBy(() ->
                validator.accept(new ToolCallDelta(MESSAGE_ID, 0, "call-1", " ")))
                .withMessage("stream tool arguments exceed maximum JSON size");
    }

    @Test
    void boundsArgumentsBufferedByMultipleOpenCalls() {
        var validator = fullPendingBuffer();
        int next = bufferedCallCount();
        validator.accept(new ToolCallStart(MESSAGE_ID, next, "call-" + next, "lookup"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                validator.accept(new ToolCallDelta(MESSAGE_ID, next, "call-" + next, "{}")))
                .withMessage("stream pending tool arguments exceed maximum buffered size");
    }

    @Test
    void releasesArgumentBufferCapacityWhenACallEnds() {
        var validator = fullPendingBuffer();
        validator.accept(new ToolCallEnd(MESSAGE_ID, 0, "call-0"));
        int next = bufferedCallCount();
        validator.accept(new ToolCallStart(MESSAGE_ID, next, "call-" + next, "lookup"));
        validator.accept(new ToolCallDelta(MESSAGE_ID, next, "call-" + next, maximumArguments()));
        for (int index = 1; index <= next; index++) {
            validator.accept(new ToolCallEnd(MESSAGE_ID, index, "call-" + index));
        }
        validator.accept(new MessageEnd(MESSAGE_ID, FinishReason.TOOL_CALLS));
        validator.complete();
    }

    @Test
    void acceptsTheMaximumNumberOfConsecutiveContentBlocks() {
        var validator = started();
        for (int index = 0; index < LlmInputLimits.MAX_CONTENT_BLOCKS; index++) {
            validator.accept(new ContentBlockStart(MESSAGE_ID, index, ContentBlockType.TEXT));
            validator.accept(new ContentBlockEnd(MESSAGE_ID, index));
        }
        validator.accept(new MessageEnd(MESSAGE_ID, FinishReason.STOP));
        validator.complete();
    }

    @ParameterizedTest
    @MethodSource("eventsAfterTermination")
    void rejectsEveryEventAfterEitherTerminalEvent(LlmStreamEvent event) {
        for (LlmStreamEvent terminal : List.of(new MessageEnd(MESSAGE_ID, FinishReason.STOP),
                new Error(MESSAGE_ID, LlmStreamError.UPSTREAM_ERROR))) {
            var validator = started();
            validator.accept(terminal);
            validator.complete();
            assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(event));
        }
    }

    static Stream<LlmStreamEvent> eventsAfterTermination() {
        return events(MESSAGE_ID).stream();
    }

    @Test
    void detectsPrematureSourceCompletionAndInvalidatesTheStream() {
        for (var validator : List.of(new LlmStreamEventValidator(), started(), toolWithArguments("{"))) {
            assertThatIllegalArgumentException().isThrownBy(validator::complete)
                    .withMessage("stream ended without a terminal event");
            assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(new MessageEnd(MESSAGE_ID, FinishReason.STOP)));
        }
    }

    @Test
    void neverExposesArgumentTextOrParserCausesAndCannotResumeAfterRejection() {
        var validator = toolWithArguments("{\"fixture-secret-argument\":invalid}");
        assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(new ToolCallEnd(MESSAGE_ID, 0, "call-1")))
                .withMessage("stream tool arguments must form one complete JSON object within the IR limits")
                .withNoCause();
        assertThatIllegalArgumentException().isThrownBy(() -> validator.accept(new Error(MESSAGE_ID, LlmStreamError.INVALID_RESPONSE)));
        assertThatIllegalArgumentException().isThrownBy(validator::complete);
    }

    private static List<Function<String, LlmStreamEvent>> messageEventConstructors() {
        return List.of(MessageStart::new,
                id -> new ContentBlockStart(id, 0, ContentBlockType.TEXT), id -> new TextDelta(id, 0, "fixture"),
                id -> new ThinkingDelta(id, 0, "fixture"), id -> new ContentBlockEnd(id, 0),
                id -> new ToolCallStart(id, 0, "call-1", "lookup"), id -> new ToolCallDelta(id, 0, "call-1", "{}"),
                id -> new ToolCallEnd(id, 0, "call-1"), id -> new UsageUpdate(id, new Usage(0, 0, 0)),
                id -> new MessageEnd(id, FinishReason.STOP));
    }

    private static List<IntFunction<LlmStreamEvent>> blockEventConstructors() {
        return List.of(index -> new ContentBlockStart(MESSAGE_ID, index, ContentBlockType.TEXT),
                index -> new TextDelta(MESSAGE_ID, index, "fixture"), index -> new ThinkingDelta(MESSAGE_ID, index, "fixture"),
                index -> new ContentBlockEnd(MESSAGE_ID, index), index -> new ToolCallStart(MESSAGE_ID, index, "call-1", "lookup"),
                index -> new ToolCallDelta(MESSAGE_ID, index, "call-1", "{}"), index -> new ToolCallEnd(MESSAGE_ID, index, "call-1"));
    }

    private static List<LlmStreamEvent> events(String messageId) {
        return Stream.concat(messageEventConstructors().stream().map(constructor -> constructor.apply(messageId)),
                Stream.of(new Error(messageId, LlmStreamError.UPSTREAM_ERROR))).toList();
    }

    private static LlmStreamEventValidator started() {
        var validator = new LlmStreamEventValidator();
        validator.accept(new MessageStart(MESSAGE_ID));
        return validator;
    }

    private static LlmStreamEventValidator toolWithArguments(String arguments) {
        var validator = started();
        validator.accept(new ToolCallStart(MESSAGE_ID, 0, "call-1", "lookup"));
        validator.accept(new ToolCallDelta(MESSAGE_ID, 0, "call-1", arguments));
        return validator;
    }

    private static void finishTool(LlmStreamEventValidator validator) {
        validator.accept(new ToolCallEnd(MESSAGE_ID, 0, "call-1"));
        validator.accept(new MessageEnd(MESSAGE_ID, FinishReason.TOOL_CALLS));
        validator.complete();
    }

    private static void validate(LlmStreamEvent... events) {
        var validator = new LlmStreamEventValidator();
        for (var event : events) {
            validator.accept(event);
        }
        validator.complete();
    }

    private static String nestedArguments(int depth) {
        return "{\"value\":" + "[".repeat(depth - 2) + "0" + "]".repeat(depth - 2) + "}";
    }

    private static String arrayArguments(int values) {
        return "{\"values\":[" + "0,".repeat(values - 1) + "0]}";
    }

    private static String maximumArguments() {
        return "{}" + " ".repeat(LlmInputLimits.MAX_JSON_CHARACTERS - 2);
    }

    private static int bufferedCallCount() {
        return LlmInputLimits.MAX_PAYLOAD_CHARACTERS / LlmInputLimits.MAX_JSON_CHARACTERS;
    }

    private static LlmStreamEventValidator fullPendingBuffer() {
        var validator = started();
        String arguments = maximumArguments();
        for (int index = 0; index < bufferedCallCount(); index++) {
            validator.accept(new ToolCallStart(MESSAGE_ID, index, "call-" + index, "lookup"));
            validator.accept(new ToolCallDelta(MESSAGE_ID, index, "call-" + index, arguments));
        }
        return validator;
    }
}
