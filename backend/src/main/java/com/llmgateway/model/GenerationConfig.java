package com.llmgateway.model;

import java.util.List;

/** Null scalar parameters mean unspecified; adapters must not silently insert provider defaults. */
public record GenerationConfig(
        Double temperature, Double topP, Integer maxTokens, Integer seed, List<String> stopSequences) {
    public GenerationConfig {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be finite and between 0 and 2");
        }
        if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("topP must be finite and between 0 and 1");
        }
        if (temperature != null && topP != null) {
            throw new IllegalArgumentException("temperature and topP are mutually exclusive");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        stopSequences = IrValidation.requiredList(stopSequences == null ? List.of() : stopSequences,
                "stop sequences", LlmInputLimits.MAX_STOP_SEQUENCES);
        for (String stop : stopSequences) {
            if (stop.isEmpty() || stop.length() > LlmInputLimits.MAX_STOP_SEQUENCE_LENGTH) {
                throw new IllegalArgumentException("stop sequences must be nonempty and within maximum length");
            }
        }
    }

    public GenerationConfig(Double temperature, Double topP, Integer maxTokens, Integer seed) {
        this(temperature, topP, maxTokens, seed, List.of());
    }
}
