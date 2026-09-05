package com.llmgateway.admin;

import com.llmgateway.model.Protocol;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("virtual_model_bindings")
public record BindingEntity(@Id String id, String virtualModelId, String providerId, String providerModelId,
                             boolean enabled, int priority, boolean translationEnabled, Protocol sourceProtocol,
                             Protocol targetProtocol, String capabilitiesOverride, Instant createdAt, Instant updatedAt, @Version Long version) {}
