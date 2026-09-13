package com.llmgateway.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationConfigTest {
    @Test
    void keepsAbsentParametersUnknownAndExplicitZeroOrNegativeSeedsIntact() {
        var unspecified = new GenerationConfig(null, null, null, null, null);

        assertThat(unspecified.temperature()).isNull();
        assertThat(unspecified.topP()).isNull();
        assertThat(unspecified.maxTokens()).isNull();
        assertThat(unspecified.seed()).isNull();
        assertThat(unspecified.stopSequences()).isEmpty();
        assertThat(new GenerationConfig(0.0, null, 1, -1).seed()).isEqualTo(-1);
        assertThat(new GenerationConfig(0.0, null, 1, 0).temperature()).isZero();
        assertThat(new GenerationConfig(null, 0.0, 1, null).topP()).isZero();
        assertThat(new GenerationConfig(2.0, null, Integer.MAX_VALUE, Integer.MIN_VALUE).temperature()).isEqualTo(2.0);
        assertThat(new GenerationConfig(null, 1.0, null, null).topP()).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 2.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void rejectsInvalidTemperature(double value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(value, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void rejectsInvalidTopP(double value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(null, value, null, null));
    }

    @Test
    void rejectsSimultaneousSamplingControlsEvenWhenBothAreZero() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(0.0, 0.0, null, null))
                .withMessage("temperature and topP are mutually exclusive");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonpositiveGenerationAndReasoningBudgets(int value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(null, null, value, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReasoningConfig(true, value));
    }

    @Test
    void onlyAllowsAnExplicitBudgetWhenReasoningIsEnabled() {
        assertThat(new ReasoningConfig(false, null).enabled()).isFalse();
        assertThat(new ReasoningConfig(true, null).budgetTokens()).isNull();
        assertThat(new ReasoningConfig(true, 1).budgetTokens()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new ReasoningConfig(false, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReasoningConfig(false, 0));
    }

    @Test
    void copiesStopSequencesAndPreservesWhitespaceAndOrder() {
        var stops = new ArrayList<>(List.of("\n", "  ", "END"));
        var generation = new GenerationConfig(null, null, null, null, stops);
        stops.clear();

        assertThat(generation.stopSequences()).containsExactly("\n", "  ", "END");
        assertThatThrownBy(() -> generation.stopSequences().add("mutated"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundsStopSequenceCountAndLengthAndRejectsMissingEntries() {
        assertThat(new GenerationConfig(null, null, null, null,
                Collections.nCopies(LlmInputLimits.MAX_STOP_SEQUENCES, "x".repeat(LlmInputLimits.MAX_STOP_SEQUENCE_LENGTH)))
                .stopSequences()).hasSize(LlmInputLimits.MAX_STOP_SEQUENCES);
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(null, null, null, null,
                Collections.nCopies(LlmInputLimits.MAX_STOP_SEQUENCES + 1, "stop")));
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(null, null, null, null,
                List.of("x".repeat(LlmInputLimits.MAX_STOP_SEQUENCE_LENGTH + 1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(null, null, null, null, List.of("")));
        assertThatIllegalArgumentException().isThrownBy(() -> new GenerationConfig(null, null, null, null,
                Arrays.asList("stop", null)));
    }
}
