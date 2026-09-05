package com.llmgateway.model;

import java.time.Instant;

public record VirtualModelBinding(
        String id, String virtualModelId, String providerId, String providerModelId,
        boolean enabled, int priority, boolean translationEnabled, Protocol sourceProtocol,
        Protocol targetProtocol, ModelCapabilities capabilitiesOverride, Instant createdAt, Instant updatedAt) {}
