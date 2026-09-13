package com.llmgateway.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
final class AdminValidation {
    private final ObjectMapper objectMapper;

    AdminValidation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static <T> Mono<T> required(Mono<T> value, String resource) {
        return value.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, resource + " not found")));
    }

    static void httpUrl(String value, String field) {
        try {
            URI uri = URI.create(value);
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Return a field-only error so credentials in an invalid URL are never reflected.
        }
        throw new IllegalArgumentException(field + " must be an HTTP(S) URL without credentials or fragments");
    }

    void json(String value, String field, boolean arrayRequired) {
        if (value == null) {
            return;
        }
        if (arrayRequired) {
            com.llmgateway.routing.CapabilityChecker.parse(value);
            return;
        }
        try {
            JsonNode node = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (node != null && (!arrayRequired || node.isArray())) {
                return;
            }
        } catch (JsonProcessingException ignored) {
            // Do not reflect the supplied JSON in validation errors.
        }
        throw new IllegalArgumentException(field + (arrayRequired ? " must be a JSON array" : " must be valid JSON"));
    }
}
