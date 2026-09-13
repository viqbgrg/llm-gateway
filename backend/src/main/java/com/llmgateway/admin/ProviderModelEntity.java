package com.llmgateway.admin;

import com.llmgateway.model.ProviderModelStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("provider_models")
public record ProviderModelEntity(@Id String id, String providerId, String modelName, String displayName,
                                  ProviderModelStatus status, String capabilities, String rawMetadata,
                                  Instant firstSeenAt, Instant lastSeenAt, Instant createdAt, Instant updatedAt, @Version Long version,
                                  int missingCount, Instant firstMissingAt, long observedGeneration, String removalSource,
                                  ProviderModelStatus preRemovalStatus, long routingVersion) {
    public ProviderModelEntity(String id, String providerId, String modelName, String displayName, ProviderModelStatus status,
                               String capabilities, String rawMetadata, Instant firstSeenAt, Instant lastSeenAt,
                               Instant createdAt, Instant updatedAt, Long version) {
        this(id, providerId, modelName, displayName, status, capabilities, rawMetadata, firstSeenAt, lastSeenAt, createdAt, updatedAt,
                version, 0, null, 0, null, null, version == null ? 0 : version);
    }
}
