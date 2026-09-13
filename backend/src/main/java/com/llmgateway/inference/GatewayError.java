package com.llmgateway.inference;

public enum GatewayError {
    INVALID_REQUEST(400, "Invalid or unsupported request"),
    AUTHENTICATION_FAILED(401, "Invalid gateway credentials"),
    PAYLOAD_TOO_LARGE(413, "Request exceeds the input size limit"),
    MODEL_NOT_FOUND(404, "Requested model is not configured"),
    MODEL_DISABLED(400, "Requested model is disabled"),
    CAPABILITY_UNSUPPORTED(400, "No binding supports the required capabilities"),
    PROVIDER_AUTHENTICATION(502, "Provider credentials are unavailable or rejected"),
    PROVIDER_MODEL_NOT_FOUND(502, "Provider model is unavailable"),
    RATE_LIMITED(429, "Provider capacity is temporarily limited"),
    CONNECTION_FAILED(502, "Unable to connect to provider"),
    NETWORK_ERROR(502, "Provider connection failed"),
    TIMEOUT(504, "Request deadline exceeded"),
    PROVIDER_ERROR(502, "Provider service failed"),
    PROVIDER_REQUEST_REJECTED(400, "Provider rejected the request"),
    INVALID_RESPONSE(502, "Provider returned an invalid response"),
    NO_CANDIDATES(503, "No available binding"),
    CONFIGURATION_CHANGED(503, "Routing configuration changed; retry the request"),
    STORAGE_UNAVAILABLE(503, "Configuration or runtime storage is unavailable"),
    CREDENTIAL_CONFIGURATION(502, "Provider credential configuration is unavailable"),
    INTERNAL_ERROR(500, "Request could not be completed");

    private final int status;
    private final String message;
    GatewayError(int status, String message) { this.status = status; this.message = message; }
    public int status() { return status; }
    public String message() { return message; }
}
