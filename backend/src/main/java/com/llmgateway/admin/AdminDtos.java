package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import com.llmgateway.model.ProviderModelStatus;

public final class AdminDtos {
    private AdminDtos() {}
    public record ProviderRequest(String name, String baseUrl, String apiKey, Boolean enabled, Protocol protocol,
                                  Long connectTimeoutMs, Long readTimeoutMs, Long requestTimeoutMs, Integer maxRetries,
                                  Boolean modelDiscoveryEnabled, String modelDiscoveryUrl, Long modelDiscoveryIntervalMs) {}
    public record ProviderResponse(String id, String name, String baseUrl, String apiKey, boolean enabled,
                                   Protocol protocol, long connectTimeoutMs, long readTimeoutMs, long requestTimeoutMs,
                                   int maxRetries, boolean modelDiscoveryEnabled, String modelDiscoveryUrl,
                                   long modelDiscoveryIntervalMs) {}
    public record ProviderModelRequest(String providerId, String modelName, String displayName,
                                       ProviderModelStatus status, String capabilities, String rawMetadata) {}
    public record VirtualModelRequest(String name, String displayName, String description, Boolean enabled,
                                      String routingPolicyId) {}
    public record BindingRequest(String virtualModelId, String providerId, String providerModelId, Boolean enabled,
                                 Integer priority, Boolean translationEnabled, Protocol sourceProtocol,
                                 Protocol targetProtocol, String capabilitiesOverride) {}
}
