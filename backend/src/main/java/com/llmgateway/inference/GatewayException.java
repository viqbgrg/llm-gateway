package com.llmgateway.inference;

import java.time.Duration;

/** Never retains an upstream exception, body, URL, or caller-controlled message. */
public final class GatewayException extends RuntimeException {
    private final GatewayError error;
    private final boolean possiblySent;
    private final Duration retryAfter;
    private java.util.Set<com.llmgateway.model.ModelCapability> missingCapabilities = java.util.Set.of();

    public GatewayException(GatewayError error) { this(error, true, Duration.ZERO); }
    public GatewayException(GatewayError error, boolean possiblySent, Duration retryAfter) {
        super(error.message(), null, false, false);
        this.error = error;
        this.possiblySent = possiblySent;
        this.retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
    }
    public GatewayError error() { return error; }
    public boolean possiblySent() { return possiblySent; }
    public Duration retryAfter() { return retryAfter; }
    public java.util.Set<com.llmgateway.model.ModelCapability> missingCapabilities() { return missingCapabilities; }
    public static GatewayException capabilities(java.util.Set<com.llmgateway.model.ModelCapability> missing) {
        var error = new GatewayException(GatewayError.CAPABILITY_UNSUPPORTED, false, Duration.ZERO);
        error.missingCapabilities = java.util.Set.copyOf(missing);
        return error;
    }
    public static GatewayException invalid() { return new GatewayException(GatewayError.INVALID_REQUEST, false, Duration.ZERO); }
    public static GatewayException response() { return new GatewayException(GatewayError.INVALID_RESPONSE); }
    public static GatewayException safe(Throwable failure) {
        return failure instanceof GatewayException known ? known : new GatewayException(GatewayError.INTERNAL_ERROR);
    }
}
