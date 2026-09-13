package com.llmgateway.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmResponseValidationTest {
    @Test
    void distinguishesMissingUsageFromExplicitlyReportedZero() {
        var unknown = new LlmResponse("response-1", "logical-model", List.of(ContentBlock.text("fixture")),
                FinishReason.STOP, null);
        var zero = new Usage(0, 0, 0);

        assertThat(unknown.usage()).isNull();
        assertThat(zero.inputTokens()).isZero();
        assertThat(zero.outputTokens()).isZero();
        assertThat(zero.totalTokens()).isZero();
        assertThat(new LlmResponse("response-2", "logical-model", List.of(), FinishReason.CONTENT_FILTER, zero).usage())
                .isEqualTo(zero);
    }

    @Test
    void preservesPartialUsageWithoutSynthesizingMissingCounters() {
        var partial = new Usage(4L, null, null);
        var absentTotal = new Usage(4L, 2L, null);

        assertThat(partial.inputTokens()).isEqualTo(4);
        assertThat(partial.outputTokens()).isNull();
        assertThat(partial.totalTokens()).isNull();
        assertThat(absentTotal.totalTokens()).isNull();
        assertThat(new Usage(null, null, 6L).inputTokens()).isNull();
        assertThat(new Usage(null, null, null).totalTokens()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE})
    void rejectsNegativeTokenCounts(long value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Usage(value, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Usage(null, value, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new Usage(null, null, value));
    }

    @ParameterizedTest
    @MethodSource("inconsistentUsage")
    void rejectsInconsistentKnownTotalsWithoutArithmeticOverflow(Long input, Long output, Long total) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Usage(input, output, total));
    }

    static Stream<Arguments> inconsistentUsage() {
        return Stream.of(Arguments.of(2L, 3L, 4L), Arguments.of(2L, 3L, 6L), Arguments.of(4L, null, 3L),
                Arguments.of(null, 4L, 3L), Arguments.of(Long.MAX_VALUE, 1L, Long.MAX_VALUE),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void acceptsLargeCountsAndConsistentPartialTotals() {
        assertThat(new Usage(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE).totalTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(new Usage(Long.MAX_VALUE, 0, Long.MAX_VALUE).inputTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(new Usage(null, 2L, 3L).totalTokens()).isEqualTo(3);
        assertThat(new Usage(2L, null, 3L).totalTokens()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = FinishReason.class, names = {"STOP", "LENGTH", "CONTENT_FILTER", "UNKNOWN"})
    void preservesExplicitFinishReasonsAndAllowsEmptyCompletedContent(FinishReason reason) {
        assertThat(new LlmResponse("response-1", "logical-model", List.of(), reason, null).finishReason()).isEqualTo(reason);
    }

    @Test
    void keepsResponseContentOrderedAndImmutable() {
        var content = new ArrayList<>(List.of(new ContentBlock.Thinking("fixture reasoning"), ContentBlock.text("fixture")));
        var response = new LlmResponse("response-1", "logical-model", content, FinishReason.STOP, null);
        content.clear();

        assertThat(response.content()).containsExactly(new ContentBlock.Thinking("fixture reasoning"), ContentBlock.text("fixture"));
        assertThatThrownBy(() -> response.content().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresToolCallsForAToolFinishReasonAndRejectsDuplicateIdsOrToolResults() {
        var call = new ToolCall("call-1", "lookup", JsonNodeFactory.instance.objectNode());
        assertThat(new LlmResponse("response-1", "logical-model", List.of(call), FinishReason.TOOL_CALLS, null).content())
                .containsExactly(call);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmResponse("response-1", "logical-model", List.of(), FinishReason.TOOL_CALLS, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmResponse("response-1", "logical-model", List.of(call, call), FinishReason.TOOL_CALLS, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmResponse("response-1", "logical-model", List.of(new ToolResult("call-1", List.of(), false)),
                        FinishReason.STOP, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresResponseIdentifiersAndLogicalModelNames(String value) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmResponse(value, "logical-model", List.of(), FinishReason.STOP, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmResponse("response-1", value, List.of(), FinishReason.STOP, null));
    }

    @Test
    void boundsResponseIdentityAndRejectsMissingContentOrFinishReason() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmResponse(
                "x".repeat(LlmInputLimits.MAX_IDENTIFIER_LENGTH + 1), "model", List.of(), FinishReason.STOP, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmResponse(
                "response-1", "x".repeat(LlmInputLimits.MAX_MODEL_NAME_LENGTH + 1), List.of(), FinishReason.STOP, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmResponse("response-1", "model", null, FinishReason.STOP, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LlmResponse("response-1", "model", Arrays.asList(ContentBlock.text("fixture"), null), FinishReason.STOP, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new LlmResponse("response-1", "model", List.of(), null, null));
    }
}
