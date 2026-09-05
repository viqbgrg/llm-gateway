package com.llmgateway.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayWebFilter implements WebFilter {
    private final AuthenticationService authenticationService;
    public GatewayWebFilter(AuthenticationService authenticationService) { this.authenticationService = authenticationService; }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestIdHeader = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
        String requestId = requestIdHeader == null || requestIdHeader.isBlank() ? UUID.randomUUID().toString() : requestIdHeader;
        exchange.getResponse().getHeaders().set("X-Request-ID", requestId);
        if (exchange.getRequest().getPath().value().startsWith("/api/admin")
                && !authenticationService.authenticate(exchange.getRequest().getHeaders())) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange).contextWrite(context -> context.put("requestId", requestId));
    }
}
