package com.llmgateway.model;

/** A null counter is unknown. A reported zero is a known zero; absent totals are never synthesized. */
public record Usage(Long inputTokens, Long outputTokens, Long totalTokens) {
    public Usage {
        nonnegative(inputTokens, "inputTokens");
        nonnegative(outputTokens, "outputTokens");
        nonnegative(totalTokens, "totalTokens");
        if (totalTokens != null) {
            if ((inputTokens != null && totalTokens < inputTokens)
                    || (outputTokens != null && totalTokens < outputTokens)) {
                throw new IllegalArgumentException("totalTokens must cover each known token count");
            }
            // Subtraction avoids overflow even when a supplied count is Long.MAX_VALUE.
            if (inputTokens != null && outputTokens != null && totalTokens - inputTokens != outputTokens) {
                throw new IllegalArgumentException("totalTokens must equal inputTokens plus outputTokens");
            }
        }
    }

    public Usage(long inputTokens, long outputTokens, long totalTokens) {
        this(Long.valueOf(inputTokens), Long.valueOf(outputTokens), Long.valueOf(totalTokens));
    }

    private static void nonnegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be nonnegative");
        }
    }
}
