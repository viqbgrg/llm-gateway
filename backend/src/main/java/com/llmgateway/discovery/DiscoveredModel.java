package com.llmgateway.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmgateway.model.ModelCapabilities;

public record DiscoveredModel(String modelName, String displayName, ModelCapabilities capabilities, JsonNode rawMetadata) {}
