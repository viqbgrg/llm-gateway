package com.llmgateway.model;

import java.time.Instant;

public record ModelRule(String id, String pattern, int priority, boolean enabled, Instant createdAt, Instant updatedAt) {}
