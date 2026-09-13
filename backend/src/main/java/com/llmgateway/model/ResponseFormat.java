package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Schema metadata can only accompany a JSON Schema response format. */
public sealed interface ResponseFormat permits ResponseFormat.Mode, ResponseFormat.JsonSchema {
    enum Mode implements ResponseFormat {
        TEXT, JSON_OBJECT
    }

    record JsonSchema(String name, String description, JsonNode schema, Boolean strict) implements ResponseFormat {
        public JsonSchema {
            IrValidation.requiredText(name, "response schema name", LlmInputLimits.MAX_TOOL_NAME_LENGTH);
            schema = IrValidation.copyJsonObject(schema, "response schema");
        }

        @Override
        public JsonNode schema() {
            return schema.deepCopy();
        }
    }
}
