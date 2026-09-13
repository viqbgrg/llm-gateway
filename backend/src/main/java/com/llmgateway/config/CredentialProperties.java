package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gateway.credentials")
public record CredentialProperties(@DefaultValue("false") boolean encryptedWrites,
                                   @DefaultValue("true") boolean allowLegacyReads,
                                   String activeKeyId, String keyringFile) {}
