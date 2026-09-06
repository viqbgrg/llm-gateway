package com.llmgateway.discovery;

import org.springframework.http.HttpStatus;

/** Safe, operator-facing errors that never retain upstream bodies, URLs, or credentials. */
public final class ProviderAccessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ProviderAccessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
