package com.llmgateway.model;

import java.time.Instant;

public record ModelRule(String id, String pattern, int priority, boolean enabled, String virtualModelId,
                        Instant createdAt, Instant updatedAt, long version) {}
