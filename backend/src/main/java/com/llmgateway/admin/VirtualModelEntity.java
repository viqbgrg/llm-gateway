package com.llmgateway.admin;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("virtual_models")
public record VirtualModelEntity(@Id String id, String name, String displayName, String description,
                                 boolean enabled, String routingPolicyId, Instant createdAt, Instant updatedAt, @Version Long version) {}
