package com.llmgateway.model;

public record GenerationConfig(Double temperature, Double topP, Integer maxTokens, Integer seed) {}
