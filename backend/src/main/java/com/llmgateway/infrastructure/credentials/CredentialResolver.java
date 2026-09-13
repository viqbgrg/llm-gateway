package com.llmgateway.infrastructure.credentials;

import reactor.core.publisher.Mono;

public interface CredentialResolver {
    Mono<String> resolve(String providerId);
}
