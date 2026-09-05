package com.llmgateway.protocol;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

public record RequestContext(ServerHttpRequest request, HttpHeaders headers, String requestId) {}
