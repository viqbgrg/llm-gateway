package com.llmgateway.model;

import java.time.Instant;

public record VirtualModel(
        String id, String name, String displayName, String description, boolean enabled,
        String routingPolicyId, Instant createdAt, Instant updatedAt) {}
