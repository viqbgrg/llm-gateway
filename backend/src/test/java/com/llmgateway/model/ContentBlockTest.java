package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentBlockTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void carriesAllContentVariantsInMessageOrder() {
        var image = new ContentBlock.Image(new MediaSource.Url(URI.create("https://fixture.invalid/image"), null));
        var call = new ToolCall("call-1", "lookup", mapper.createObjectNode().put("query", "fixture"));
        var result = new ToolResult("call-1", List.of(ContentBlock.text("found"), image), false);
        List<ContentBlock> blocks = List.of(ContentBlock.text("hello"), image,
                new ContentBlock.Thinking("considering"), call, result,
                new ContentBlock.Audio(new MediaSource.InlineData("AQ==", "audio/wav")),
                new ContentBlock.Video(new MediaSource.InlineData("Ag==", "video/mp4")),
                new ContentBlock.Document(new MediaSource.InlineData("Aw==", "application/pdf")));

        var messages = List.of(new Message(MessageRole.USER, blocks.subList(0, 2)),
                new Message(MessageRole.ASSISTANT, blocks.subList(2, 4)),
                new Message(MessageRole.TOOL, blocks.subList(4, 5)),
                new Message(MessageRole.USER, blocks.subList(5, 8)));
        var content = messages.stream().flatMap(message -> message.content().stream()).toList();

        assertThat(content).containsExactlyElementsOf(blocks);
        assertThat(content).extracting(ContentBlock::type).containsExactly(
                ContentBlockType.TEXT, ContentBlockType.IMAGE, ContentBlockType.THINKING,
                ContentBlockType.TOOL_CALL, ContentBlockType.TOOL_RESULT, ContentBlockType.AUDIO,
                ContentBlockType.VIDEO, ContentBlockType.DOCUMENT);
        assertThat(result.toolCallId()).isEqualTo(call.id());
        assertThat(result.content()).containsExactly(new ContentBlock.Text("found"), image);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "line one\n第二行\t"})
    void preservesTextAndThinkingIncludingEmptyContent(String value) {
        assertThat(ContentBlock.text(value)).isEqualTo(new ContentBlock.Text(value));
        assertThat(((ContentBlock.Text) ContentBlock.text(value)).text()).isEqualTo(value);
        assertThat(new ContentBlock.Thinking(value).text()).isEqualTo(value);
    }

    @Test
    void rejectsMissingVariantPayloads() {
        assertThatNullPointerException().isThrownBy(() -> new ContentBlock.Text(null));
        assertThatNullPointerException().isThrownBy(() -> new ContentBlock.Thinking(null));
        assertThatNullPointerException().isThrownBy(() -> new ContentBlock.Image(null));
        assertThatNullPointerException().isThrownBy(() -> new ContentBlock.Audio(null));
        assertThatNullPointerException().isThrownBy(() -> new ContentBlock.Video(null));
        assertThatNullPointerException().isThrownBy(() -> new ContentBlock.Document(null));
    }

    @Test
    void isolatesToolArgumentsFromMutationsOnInputAndOnRead() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"options":{"labels":["original"],"enabled":true},"limit":2,"optional":null}
                """);
        JsonNode expected = input.deepCopy();
        var call = new ToolCall("call-1", "lookup", input);
        int originalHash = call.hashCode();

        ((ArrayNode) input.path("options").path("labels")).add("mutated input");
        input.put("limit", 100);
        ObjectNode exposed = (ObjectNode) call.arguments();
        ((ArrayNode) exposed.path("options").path("labels")).removeAll();
        exposed.remove("optional");

        assertThat(call.arguments()).isEqualTo(expected);
        assertThat(call).isEqualTo(new ToolCall("call-1", "lookup", expected));
        assertThat(call.hashCode()).isEqualTo(originalHash);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresToolNamesAndCallIdentifiers(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolCall(value, "lookup", mapper.createObjectNode()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolCall("call-1", value, mapper.createObjectNode()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolResult(value, List.of(), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "1", "true", "\"value\""})
    void requiresCompleteJsonObjectsForToolArguments(String json) throws Exception {
        JsonNode arguments = mapper.readTree(json);
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCall("call-1", "lookup", arguments))
                .withMessage("tool arguments must be a JSON object");
    }

    @Test
    void rejectsMissingToolArgumentsInsteadOfInventingAnEmptyObject() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCall("call-1", "lookup", null))
                .withMessage("tool arguments must be a JSON object");
    }

    @ParameterizedTest
    @MethodSource("nonJsonValues")
    void rejectsMutableOrNonJsonNodesNestedInToolArguments(JsonNode value) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.set("nested", mapper.createArrayNode().add(value));

        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCall("call-1", "lookup", arguments))
                .withMessage("tool arguments must contain only JSON values");
    }

    static Stream<JsonNode> nonJsonValues() {
        var factory = JsonNodeFactory.instance;
        return Stream.of(factory.pojoNode(new StringBuilder("mutable")), factory.binaryNode(new byte[]{1}),
                factory.missingNode(), factory.numberNode(Double.NaN), factory.numberNode(Double.POSITIVE_INFINITY),
                factory.numberNode(Float.NEGATIVE_INFINITY));
    }

    @Test
    void keepsToolResultContentOrderedAndImmutable() {
        var text = ContentBlock.text("{\"value\":1}");
        var document = new ContentBlock.Document(new MediaSource.InlineData("AQ==", "application/pdf"));
        var input = new ArrayList<ContentBlock>(List.of(text, document));
        var result = new ToolResult("call-1", input, true);

        input.clear();

        assertThat(result.content()).containsExactly(text, document);
        assertThat(result.isError()).isTrue();
        assertThatThrownBy(() -> result.content().add(ContentBlock.text("extra")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void distinguishesEmptyToolResultsFromMissingOrNullContent() {
        assertThat(new ToolResult("call-1", List.of(), false).content()).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> new ToolResult("call-1", null, false));
        assertThatNullPointerException().isThrownBy(() -> new ToolResult("call-1",
                Arrays.asList(ContentBlock.text("valid"), null), false));
    }

    @ParameterizedTest
    @MethodSource("nonResultBlocks")
    void rejectsThinkingAndNestedToolOperationsInsideToolResults(ContentBlock block) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolResult("call-1", List.of(block), false))
                .withMessage("tool results may contain only text and media blocks");
    }

    static Stream<ContentBlock> nonResultBlocks() {
        return Stream.of(new ContentBlock.Thinking("reasoning"),
                new ToolCall("call-2", "lookup", JsonNodeFactory.instance.objectNode()),
                new ToolResult("call-2", List.of(), false));
    }
}
