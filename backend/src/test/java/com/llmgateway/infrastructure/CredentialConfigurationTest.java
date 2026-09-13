package com.llmgateway.infrastructure;

import com.llmgateway.admin.ProviderRepository;
import com.llmgateway.config.CredentialProperties;
import com.llmgateway.config.GatewayProperties;
import com.llmgateway.infrastructure.credentials.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CredentialConfigurationTest {
    private CredentialService production(GatewayProperties gateway) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return new CredentialService(mock(ProviderRepository.class), mock(DatabaseClient.class),
                new CredentialProperties(true, false, "fixture", SyntheticKeyring.file()), gateway, environment);
    }

    @Test
    void productionRequiresAnIndependentAdminKey() {
        assertThatThrownBy(() -> production(new GatewayProperties("fixture-client", "fixture")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct");
        assertThatThrownBy(() -> production(new GatewayProperties("fixture-client", "fixture", "fixture-client")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct");
        assertThatCode(() -> production(new GatewayProperties("fixture-client", "fixture", "fixture-admin")))
                .doesNotThrowAnyException();
    }
}
