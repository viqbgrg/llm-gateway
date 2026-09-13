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

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class JsonSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void isolatesToolAndResponseSchemasFromMutationsOnInputAndRead() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"type":"object","properties":{"labels":{"type":"array","items":{"type":"string"}}},
                 "required":["labels"],"additionalProperties":false}
                """);
        JsonNode expected = input.deepCopy();
        var tool = new ToolDefinition("lookup", "fixture tool", input);
        var response = new ResponseFormat.JsonSchema("answer", "fixture response", input, true);
        int toolHash = tool.hashCode();
        int responseHash = response.hashCode();

        ((ArrayNode) input.get("required")).add("mutated input");
        ((ObjectNode) input.get("properties")).removeAll();
        ((ArrayNode) tool.inputSchema().get("required")).removeAll();
        ((ObjectNode) response.schema().get("properties")).removeAll();

        assertThat(tool.inputSchema()).isEqualTo(expected);
        assertThat(response.schema()).isEqualTo(expected);
        assertThat(tool).isEqualTo(new ToolDefinition("lookup", "fixture tool", expected));
        assertThat(response).isEqualTo(new ResponseFormat.JsonSchema("answer", "fixture response", expected, true));
        assertThat(tool.hashCode()).isEqualTo(toolHash);
        assertThat(response.hashCode()).isEqualTo(responseHash);
    }

    @Test
    void retainsOptionalDescriptionsAndStrictnessWithoutInventingDefaults() {
        var schema = mapper.createObjectNode();
        var tool = new ToolDefinition("lookup", null, schema);
        var response = new ResponseFormat.JsonSchema("answer", null, schema, null);

        assertThat(tool.description()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.strict()).isNull();
        assertThat(new ResponseFormat.JsonSchema("answer", "", schema, false).strict()).isFalse();
        assertThat(new ResponseFormat.JsonSchema("answer", "", schema, false).description()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void requiresToolChoiceDefinitionAndResponseSchemaNames(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolChoice.Named(name));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolDefinition(name, null, mapper.createObjectNode()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ResponseFormat.JsonSchema(name, null, mapper.createObjectNode(), null));
    }

    @Test
    void boundsToolNamesAndCallIdentifiers() {
        String name = "n".repeat(LlmInputLimits.MAX_TOOL_NAME_LENGTH);
        String id = "i".repeat(LlmInputLimits.MAX_IDENTIFIER_LENGTH);
        var schema = mapper.createObjectNode();
        assertThat(new ToolChoice.Named(name).name()).isEqualTo(name);
        assertThat(new ToolDefinition(name, null, schema).name()).isEqualTo(name);
        assertThat(new ResponseFormat.JsonSchema(name, null, schema, null).name()).isEqualTo(name);
        assertThat(new ToolCall(id, name, schema).id()).isEqualTo(id);
        assertThat(new ToolResult(id, List.of(), false).toolCallId()).isEqualTo(id);
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolChoice.Named(name + "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolDefinition(name + "x", null, schema));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResponseFormat.JsonSchema(name + "x", null, schema, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCall(id, name + "x", schema));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCall(id + "x", name, schema));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolResult(id + "x", List.of(), false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "1", "true", "\"schema\""})
    void rejectsNonObjectSchemas(String json) throws Exception {
        JsonNode schema = mapper.readTree(json);
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolDefinition("lookup", null, schema));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResponseFormat.JsonSchema("answer", null, schema, true));
    }

    @Test
    void rejectsMissingSchemas() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolDefinition("lookup", null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResponseFormat.JsonSchema("answer", null, null, null));
    }

    @ParameterizedTest
    @MethodSource("nonJsonValues")
    void rejectsMutableAndNonJsonValuesInsideSchemas(JsonNode value) {
        var schema = mapper.createObjectNode();
        schema.set("nested", mapper.createArrayNode().add(value));
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolDefinition("lookup", null, schema))
                .withMessage("tool input schema must contain only JSON values");
        assertThatIllegalArgumentException().isThrownBy(() -> new ResponseFormat.JsonSchema("answer", null, schema, null))
                .withMessage("response schema must contain only JSON values");
    }

    static Stream<JsonNode> nonJsonValues() {
        var factory = JsonNodeFactory.instance;
        return Stream.of(factory.pojoNode(new StringBuilder("private-mutable")), factory.binaryNode(new byte[]{1}),
                factory.missingNode(), factory.numberNode(Double.NaN), factory.numberNode(Double.POSITIVE_INFINITY),
                factory.numberNode(Float.NEGATIVE_INFINITY));
    }

    @Test
    void boundsJsonDepthBeforeCopyingAndRejectsCyclicTrees() {
        var schema = mapper.createObjectNode();
        var current = schema;
        for (int i = 1; i < LlmInputLimits.MAX_JSON_DEPTH; i++) {
            current = current.putObject("nested");
        }
        assertThat(new ToolDefinition("lookup", null, schema).inputSchema()).isEqualTo(schema);
        current.putObject("extra");
        assertRejectedJson(schema, "exceeds maximum JSON depth");
        var cyclic = mapper.createObjectNode();
        cyclic.set("self", cyclic);
        assertRejectedJson(cyclic, "exceeds maximum JSON depth");
    }

    @Test
    void boundsJsonNodeCountsBeforeCopying() {
        var schema = mapper.createObjectNode();
        var nodes = schema.putArray("items");
        for (int i = 2; i < LlmInputLimits.MAX_JSON_NODES; i++) {
            nodes.addNull();
        }
        assertThat(new ToolDefinition("lookup", null, schema).inputSchema()).isEqualTo(schema);
        nodes.addNull();
        assertRejectedJson(schema, "exceeds maximum JSON node count");
    }

    @Test
    void boundsSerializedJsonSizeIncludingEscapingAndPropertyNames() {
        var atLimit = mapper.createObjectNode().put("x", "x".repeat(LlmInputLimits.MAX_JSON_CHARACTERS - 8));
        assertThat(atLimit.toString()).hasSize(LlmInputLimits.MAX_JSON_CHARACTERS);
        assertThat(new ToolDefinition("lookup", null, atLimit).inputSchema()).isEqualTo(atLimit);
        assertRejectedJson(mapper.createObjectNode().put("x", "x".repeat(LlmInputLimits.MAX_JSON_CHARACTERS - 7)),
                "exceeds maximum JSON size");
        assertRejectedJson(mapper.createObjectNode().put("x", "\n".repeat(LlmInputLimits.MAX_JSON_CHARACTERS / 2)),
                "exceeds maximum JSON size");
        assertRejectedJson(mapper.createObjectNode().put("x".repeat(LlmInputLimits.MAX_JSON_CHARACTERS + 1), 1),
                "exceeds maximum JSON size");
    }

    private void assertRejectedJson(JsonNode json, String reason) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolDefinition("lookup", null, json))
                .withMessage("tool input schema " + reason);
        assertThatIllegalArgumentException().isThrownBy(() -> new ResponseFormat.JsonSchema("answer", null, json, null))
                .withMessage("response schema " + reason);
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolCall("call-1", "lookup", json))
                .withMessage("tool arguments " + reason);
    }
}
