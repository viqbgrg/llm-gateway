package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Duration;
import java.time.Instant;

@Table("providers")
public record ProviderEntity(@Id String id, String name, String baseUrl, String apiKey, boolean enabled,
                             Protocol protocol, long connectTimeoutMs, long readTimeoutMs, long requestTimeoutMs,
                             int maxRetries, boolean modelDiscoveryEnabled, String modelDiscoveryUrl,
                             long modelDiscoveryIntervalMs, Instant createdAt, Instant updatedAt, @Version Long version) {}
