package com.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ProviderModel(
        String id, String providerId, String modelName, String displayName, ProviderModelStatus status,
        ModelCapabilities capabilities, JsonNode rawMetadata, Instant firstSeenAt, Instant lastSeenAt,
        Instant createdAt, Instant updatedAt) {}
