package com.llmgateway.admin;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("model_rules")
public record ModelRuleEntity(@Id String id, String pattern, int priority, boolean enabled, String virtualModelId,
                              Instant createdAt, Instant updatedAt, @Version Long version) {}
