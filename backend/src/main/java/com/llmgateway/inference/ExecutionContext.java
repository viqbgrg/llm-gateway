package com.llmgateway.inference;

import com.llmgateway.protocol.RequestContext;
import com.llmgateway.resilience.AttemptBudget;
import com.llmgateway.routing.ConfigurationSnapshot;

public record ExecutionContext(RequestContext request, String logicalModel, ConfigurationSnapshot configuration, AttemptBudget budget) {}
