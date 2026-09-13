package com.llmgateway.protocol;

import com.llmgateway.model.Protocol;
import java.time.Instant;

public record RequestContext(String requestId, Protocol protocol, Instant enteredAt, long startedNanos) {}
