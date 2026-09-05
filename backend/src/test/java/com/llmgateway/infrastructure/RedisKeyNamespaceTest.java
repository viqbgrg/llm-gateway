package com.llmgateway.infrastructure;

import com.llmgateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeyNamespaceTest {
    @Test
    void buildsStableRuntimeKeys() {
        RedisKeyNamespace keys = new RedisKeyNamespace(new GatewayProperties("secret", "llm-gateway"));
        assertEquals("llm-gateway:health:binding:b-1", keys.bindingHealth("b-1"));
        assertEquals("llm-gateway:circuit:binding:b-1", keys.bindingCircuit("b-1"));
        assertEquals("llm-gateway:preferred:virtual-model:v-1", keys.preferredVirtualModel("v-1"));
    }
}
