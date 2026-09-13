package com.llmgateway.infrastructure;

import org.springframework.stereotype.Component;

@Component
public class RedisKeyNamespace {
    private final String prefix;
    public RedisKeyNamespace(com.llmgateway.config.GatewayProperties properties) { this.prefix = properties.redisKeyPrefix(); }
    public String providerHealth(String id) { return prefix + ":health:provider:" + id; }
    public String bindingHealth(String id) { return prefix + ":health:binding:" + id; }
    public String modelHealth(String id) { return prefix + ":health:model:" + id; }
    public String bindingCircuit(String id) { return prefix + ":circuit:binding:" + id; }
    public String preferredVirtualModel(String id) { return prefix + ":preferred:virtual-model:" + id; }
    public String bindingScore(String id) { return prefix + ":score:binding:" + id; }
    public String providerModels(String id) { return prefix + ":provider-models:" + id; }
    public String discoveryLease(String id) { return prefix + ":discovery:lease:" + id; }
    public String discoveryStatus(String id) { return prefix + ":discovery:status:" + id; }
    public String dashboardMinute(long minute) { return prefix + ":dashboard:minute:" + minute; }
    public String exploration(String id) { return prefix + ":exploration:virtual-model:" + id; }
}
