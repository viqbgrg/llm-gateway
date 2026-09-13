package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import com.llmgateway.model.ProviderModelStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AdminDtos {
    private AdminDtos() {}
    public record ProviderRequest(@NotBlank @Size(max = 128) String name,
                                  @NotBlank @Size(max = 512) String baseUrl,
                                  @Size(max = 1024) String apiKey, Boolean enabled, Protocol protocol,
                                  @Min(1) @Max(Integer.MAX_VALUE) Long connectTimeoutMs,
                                  @Min(1) @Max(600000) Long readTimeoutMs, @Min(1) @Max(600000) Long requestTimeoutMs, @Min(0) @Max(20) Integer maxRetries,
                                  Boolean modelDiscoveryEnabled, @Size(max = 512) String modelDiscoveryUrl,
                                  @Min(1) @Max(86400000) Long modelDiscoveryIntervalMs) {}
    public record ProviderResponse(String id, String name, String baseUrl, String apiKey, boolean enabled,
                                   Protocol protocol, long connectTimeoutMs, long readTimeoutMs, long requestTimeoutMs,
                                   int maxRetries, boolean modelDiscoveryEnabled, String modelDiscoveryUrl,
                                   long modelDiscoveryIntervalMs) {}
    public record ProviderModelRequest(@NotBlank @Size(max = 36) String providerId,
                                       @NotBlank @Size(max = 255) String modelName,
                                       @Size(max = 255) String displayName,
                                       ProviderModelStatus status, String capabilities, String rawMetadata) {}
    public record VirtualModelRequest(@NotBlank @Size(max = 255) String name,
                                      @Size(max = 255) String displayName, String description, Boolean enabled,
                                      @Size(max = 36) String routingPolicyId) {}
    public record BindingRequest(@NotBlank @Size(max = 36) String virtualModelId,
                                 @NotBlank @Size(max = 36) String providerId,
                                 @NotBlank @Size(max = 36) String providerModelId, Boolean enabled,
                                 @Min(0) Integer priority, Boolean translationEnabled, Protocol sourceProtocol,
                                 Protocol targetProtocol, String capabilitiesOverride) {}
    public record ConnectionTestResponse(boolean success, int modelCount, long latencyMs) {}
    public record ModelSyncResponse(int created, int updated, int total) {}
}
