package com.llmgateway.provider;

import com.llmgateway.model.Provider;

public record ProviderContext(Provider provider, String requestId, String actualModel) {}
