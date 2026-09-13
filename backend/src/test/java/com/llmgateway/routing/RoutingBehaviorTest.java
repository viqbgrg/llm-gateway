package com.llmgateway.routing;

import com.llmgateway.admin.*;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import com.llmgateway.protocol.TranslationValidator;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutingBehaviorTest {
    @ParameterizedTest @CsvSource({"model-*,model-a,true", "model-?,model-aa,false", "MODEL*,model-a,false", "a.b,aXb,false", "a.b,a.b,true", "*?*,x,true", "[a],a,false", "ab**c,abccc,true"})
    void matchesOnlyTheDocumentedWildcardGrammar(String pattern, String input, boolean expected) {
        assertThat(WildcardMatcher.matches(pattern, input)).isEqualTo(expected);
    }
    @Test void aDisabledExactNameCannotBeBypassedByARule() {
        var models = mock(VirtualModelRepository.class); var rules = mock(ModelRuleRepository.class);
        when(models.findByName("public")).thenReturn(Mono.just(new VirtualModelEntity("id", "public", null, null, false, null, Instant.EPOCH, Instant.EPOCH, 1L)));
        StepVerifier.create(new VirtualModelResolver(models, rules).resolve("public")).expectErrorSatisfies(e -> assertThat(((GatewayException) e).error()).isEqualTo(GatewayError.MODEL_DISABLED)).verify();
        verifyNoInteractions(rules);
    }
    @Test void ruleOrderingUsesPriorityThenLiteralSpecificityThenStableIds() {
        var models = mock(VirtualModelRepository.class); var rules = mock(ModelRuleRepository.class);
        when(models.findByName("ab-x")).thenReturn(Mono.empty());
        when(rules.findByEnabledTrue()).thenReturn(Flux.just(
                new ModelRuleEntity("z", "*", 0, true, "fallback", Instant.EPOCH, Instant.EPOCH, 1L),
                new ModelRuleEntity("b", "ab-*", 0, true, "selected", Instant.EPOCH, Instant.EPOCH, 1L),
                new ModelRuleEntity("a", "ab-?", 1, true, "other", Instant.EPOCH, Instant.EPOCH, 1L)));
        when(models.findById("selected")).thenReturn(Mono.just(new VirtualModelEntity("selected", "target", null, null, true, null, Instant.EPOCH, Instant.EPOCH, 1L)));
        StepVerifier.create(new VirtualModelResolver(models, rules).resolve("ab-x")).assertNext(v -> assertThat(v.id()).isEqualTo("selected")).verifyComplete();
    }
    @Test void overridingCannotAddCapabilitiesTheAdapterDoesNotImplement() {
        var model = new ModelCapabilities(Set.of(ModelCapability.CHAT, ModelCapability.TOOLS));
        var override = new ModelCapabilities(Set.of(ModelCapability.CHAT, ModelCapability.AUDIO));
        assertThat(CapabilityChecker.effective(model, override, Set.of(ModelCapability.CHAT, ModelCapability.TOOLS))).containsExactly(ModelCapability.CHAT);
        assertThat(CapabilityChecker.effective(model, ModelCapabilities.empty(), Set.of(ModelCapability.CHAT))).isEmpty();
        assertThatThrownBy(() -> CapabilityChecker.parse("[\"UNKNOWN\"]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CapabilityChecker.parse("[4]")).isInstanceOf(IllegalArgumentException.class);
        LlmRequest request = new LlmRequest("m", List.of(new Message(MessageRole.USER, List.of(ContentBlock.text("x")))), List.of(), null, null, ResponseFormat.Mode.JSON_OBJECT, true);
        assertThat(RequestCapabilityExtractor.extract(request)).containsExactlyInAnyOrder(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT, ModelCapability.STREAMING);
    }
    @Test void onlyImplementedChainsCanBeEnabled() {
        var validator = new TranslationValidator();
        for (Protocol source : Protocol.values()) for (Protocol target : Protocol.values()) {
            assertThat(validator.available(source, target, target, true)).isEqualTo(target == Protocol.CHAT_COMPLETIONS);
            assertThat(validator.available(source, target, target, false)).isEqualTo(source == Protocol.CHAT_COMPLETIONS && target == Protocol.CHAT_COMPLETIONS);
        }
    }
}
