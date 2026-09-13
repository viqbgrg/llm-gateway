package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Errors identify fields and constraints only, never request content. */
final class IrValidation {
    private IrValidation() {}

    static void requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
    }

    static <T> List<T> requiredList(List<T> values, String field, int maxSize) {
        if (values == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(field + " exceeds maximum count");
        }
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " must not contain null entries");
            }
        }
        return List.copyOf(values);
    }

    static JsonNode copyJsonObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        // Bound traversal before serialization/deepCopy, including cyclic programmatically built trees.
        new JsonBudget(field).validate(value, 1);
        if (jsonLength(value) > LlmInputLimits.MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException(field + " exceeds maximum JSON size");
        }
        return value.deepCopy();
    }

    static int jsonLength(JsonNode value) {
        return value.toString().length();
    }

    private static final class JsonBudget {
        private final String field;
        private int nodes;
        private long characters;

        private JsonBudget(String field) {
            this.field = field;
        }

        private void validate(JsonNode value, int depth) {
            if (depth > LlmInputLimits.MAX_JSON_DEPTH) {
                throw new IllegalArgumentException(field + " exceeds maximum JSON depth");
            }
            if (++nodes > LlmInputLimits.MAX_JSON_NODES) {
                throw new IllegalArgumentException(field + " exceeds maximum JSON node count");
            }
            switch (value.getNodeType()) {
                case OBJECT -> {
                    var fields = value.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        addCharacters(entry.getKey().length());
                        validate(entry.getValue(), depth + 1);
                    }
                }
                case ARRAY -> {
                    for (JsonNode child : value) {
                        validate(child, depth + 1);
                    }
                }
                case STRING -> addCharacters(value.textValue().length());
                case NUMBER -> {
                    if ((value.isDouble() || value.isFloat()) && !Double.isFinite(value.doubleValue())) {
                        throw nonJsonValue();
                    }
                    addCharacters(value.asText().length());
                }
                case BOOLEAN, NULL -> { }
                default -> throw nonJsonValue();
            }
        }

        private void addCharacters(int count) {
            characters += count;
            if (characters > LlmInputLimits.MAX_JSON_CHARACTERS) {
                throw new IllegalArgumentException(field + " exceeds maximum JSON size");
            }
        }

        private IllegalArgumentException nonJsonValue() {
            return new IllegalArgumentException(field + " must contain only JSON values");
        }
    }
}
