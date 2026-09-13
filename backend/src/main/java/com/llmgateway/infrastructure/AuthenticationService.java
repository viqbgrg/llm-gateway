package com.llmgateway.infrastructure;

import com.llmgateway.config.GatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private final GatewayProperties properties;
    public AuthenticationService(GatewayProperties properties) { this.properties = properties; }
    public boolean authenticate(HttpHeaders headers) {
        return bearer(headers, properties.apiKey());
    }
    public boolean authenticateAdmin(HttpHeaders headers) {
        return bearer(headers, properties.adminApiKey());
    }
    private static boolean bearer(HttpHeaders headers, String configured) {
        if (configured.isBlank()) return false;
        if (headers.getOrEmpty(HttpHeaders.AUTHORIZATION).size() != 1 || headers.containsKey("x-api-key")) return false;
        String value = headers.getFirst(HttpHeaders.AUTHORIZATION);
        return value != null && value.startsWith("Bearer ") && equal(configured, value.substring(7));
    }
    public boolean authenticateAnthropic(HttpHeaders headers) {
        if (headers.containsKey("x-api-key")) {
            return !headers.containsKey(HttpHeaders.AUTHORIZATION) && headers.getOrEmpty("x-api-key").size() == 1
                    && !properties.apiKey().isBlank() && equal(properties.apiKey(), headers.getFirst("x-api-key"));
        }
        return authenticate(headers);
    }
    private static boolean equal(String configured, String value) {
        return value != null && java.security.MessageDigest.isEqual(configured.getBytes(java.nio.charset.StandardCharsets.UTF_8), value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
