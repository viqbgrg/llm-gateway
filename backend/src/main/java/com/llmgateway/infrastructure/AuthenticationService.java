package com.llmgateway.infrastructure;

import com.llmgateway.config.GatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private final GatewayProperties properties;
    public AuthenticationService(GatewayProperties properties) { this.properties = properties; }
    public boolean authenticate(HttpHeaders headers) {
        String configured = properties.apiKey();
        if (configured.isBlank()) return true;
        String value = headers.getFirst(HttpHeaders.AUTHORIZATION);
        return value != null && value.startsWith("Bearer ") && configured.equals(value.substring(7));
    }
}
