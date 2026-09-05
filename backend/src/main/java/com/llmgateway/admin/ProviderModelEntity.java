package com.llmgateway.admin;

import com.llmgateway.model.ProviderModelStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("provider_models")
public record ProviderModelEntity(@Id String id, String providerId, String modelName, String displayName,
                                  ProviderModelStatus status, String capabilities, String rawMetadata,
                                  Instant firstSeenAt, Instant lastSeenAt, Instant createdAt, Instant updatedAt, @Version Long version) {}
