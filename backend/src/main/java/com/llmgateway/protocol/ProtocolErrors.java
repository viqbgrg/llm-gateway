package com.llmgateway.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import static com.llmgateway.protocol.ProtocolJson.*;

public final class ProtocolErrors {
    private ProtocolErrors() {}
    public static ObjectNode body(Protocol protocol, GatewayException failure) {
        String code = failure.error().name().toLowerCase(java.util.Locale.ROOT);
        ObjectNode detail = object().put("message", failure.getMessage()).put("code", code);
        if (!failure.missingCapabilities().isEmpty()) detail.set("missing_capabilities", MAPPER.valueToTree(failure.missingCapabilities().stream().map(Enum::name).sorted().toList()));
        if (protocol == Protocol.ANTHROPIC) {
            detail.put("type", failure.error() == GatewayError.AUTHENTICATION_FAILED ? "authentication_error"
                    : failure.error().status() == 400 || failure.error().status() == 413 ? "invalid_request_error"
                    : failure.error().status() == 429 ? "rate_limit_error" : "api_error");
            return object().put("type", "error").set("error", detail);
        }
        detail.put("type", failure.error().status() < 500 ? "invalid_request_error" : "server_error").putNull("param");
        return object().set("error", detail);
    }
    public static LlmStreamError stream(GatewayException failure) {
        return switch (failure.error()) {
            case TIMEOUT -> LlmStreamError.TIMEOUT;
            case RATE_LIMITED -> LlmStreamError.RATE_LIMITED;
            case INVALID_RESPONSE -> LlmStreamError.INVALID_RESPONSE;
            default -> LlmStreamError.UPSTREAM_ERROR;
        };
    }
}
