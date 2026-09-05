package com.llmgateway.model;

import java.util.EnumSet;
import java.util.Set;

public record ModelCapabilities(Set<ModelCapability> values) {
    public ModelCapabilities {
        values = values == null ? Set.of() : Set.copyOf(values);
    }

    public static ModelCapabilities empty() {
        return new ModelCapabilities(EnumSet.noneOf(ModelCapability.class));
    }

    public boolean supports(ModelCapability capability) {
        return values.contains(capability);
    }
}
