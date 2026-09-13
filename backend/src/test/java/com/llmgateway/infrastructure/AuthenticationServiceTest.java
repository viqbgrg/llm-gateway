package com.llmgateway.infrastructure;

import com.llmgateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationServiceTest {
    @Test
    void acceptsOnlyConfiguredBearerToken() {
        AuthenticationService service = new AuthenticationService(new GatewayProperties("secret", "llm-gateway"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("secret");
        assertTrue(service.authenticate(headers));
        headers.setBearerAuth("wrong");
        assertFalse(service.authenticate(headers));
    }

    @Test
    void separatesInferenceAndAdministrationCredentials() {
        AuthenticationService service = new AuthenticationService(new GatewayProperties("client-key", "fixture", "admin-key"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("client-key");
        assertTrue(service.authenticate(headers));
        assertFalse(service.authenticateAdmin(headers));
        headers.setBearerAuth("admin-key");
        assertFalse(service.authenticate(headers));
        assertTrue(service.authenticateAdmin(headers));
        headers.clear();
        headers.set("x-api-key", "client-key");
        assertTrue(service.authenticateAnthropic(headers));
        assertFalse(service.authenticateAdmin(headers));
    }
}
