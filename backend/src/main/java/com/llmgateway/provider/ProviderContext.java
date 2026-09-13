package com.llmgateway.provider;

import com.llmgateway.model.Provider;
import java.time.Duration;

public record ProviderContext(Provider provider, String requestId, String attemptId, String bindingId,
                              String actualModel, Duration remaining, Runnable onDispatch, Runnable onTransportEvent) {}
