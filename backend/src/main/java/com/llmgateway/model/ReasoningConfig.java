package com.llmgateway.model;

public record ReasoningConfig(boolean enabled, Integer budgetTokens) {
    public ReasoningConfig {
        if (!enabled && budgetTokens != null) {
            throw new IllegalArgumentException("reasoning budget requires enabled reasoning");
        }
        if (budgetTokens != null && budgetTokens <= 0) {
            throw new IllegalArgumentException("reasoning budget must be positive");
        }
    }
}
