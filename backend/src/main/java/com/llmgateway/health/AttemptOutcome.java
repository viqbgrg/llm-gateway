package com.llmgateway.health;

public enum AttemptOutcome {
    SUCCESS, PROVIDER_ERROR, TIMEOUT, RATE_LIMITED, AUTHENTICATION_ERROR, INVALID_RESPONSE,
    REQUEST_ERROR, CLIENT_CANCELLED, HEDGE_CANCELLED;
    public boolean failure() { return this == PROVIDER_ERROR || this == TIMEOUT || this == INVALID_RESPONSE; }
    public boolean cancellation() { return this == CLIENT_CANCELLED || this == HEDGE_CANCELLED; }
    public boolean sample() { return this == SUCCESS || failure(); }
}
