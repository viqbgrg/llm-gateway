package com.llmgateway.model;

/** Safe stream failures. Adapters map these values to their own protocol's error envelope. */
public enum LlmStreamError {
    UPSTREAM_ERROR("upstream_error", "The upstream request failed."),
    TIMEOUT("timeout", "The upstream request timed out."),
    RATE_LIMITED("rate_limited", "The upstream request was rate limited."),
    INVALID_RESPONSE("invalid_response", "The upstream response is invalid."),
    INTERNAL_ERROR("internal_error", "The request could not be completed.");

    private final String code;
    private final String message;

    LlmStreamError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
