package com.llmgateway.infrastructure;

import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import com.llmgateway.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayWebFilter implements WebFilter {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GatewayWebFilter.class);
    public static final String REQUEST_CONTEXT = "gateway.requestContext";
    public static final String OUTCOME = "gateway.outcome";
    public static final String STREAM = "gateway.stream";
    public static final String USAGE = "gateway.usage";
    private final AuthenticationService authentication;
    private final Clock clock;
    private final GatewayMetrics metrics;
    private final com.llmgateway.health.DashboardStore dashboard;
    public GatewayWebFilter(AuthenticationService authentication, Clock clock, GatewayMetrics metrics, com.llmgateway.health.DashboardStore dashboard) {
        this.authentication = authentication; this.clock = clock; this.metrics = metrics;
        this.dashboard = dashboard;
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
        String id = supplied != null && supplied.matches("[A-Za-z0-9._-]{1,128}") ? supplied : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-ID", id);
        String path = exchange.getRequest().getPath().value();
        Protocol protocol = path.equals("/v1/messages") ? Protocol.ANTHROPIC : path.equals("/v1/responses") ? Protocol.RESPONSES : Protocol.CHAT_COMPLETIONS;
        boolean inference = path.startsWith("/v1/");
        RequestContext context = new RequestContext(id, protocol, clock.instant(), System.nanoTime());
        exchange.getAttributes().put(REQUEST_CONTEXT, context);
        boolean protectedPath = inference || path.startsWith("/api/admin") || path.startsWith("/actuator") && !path.startsWith("/actuator/health");
        Mono<Void> operation = Mono.defer(() -> {
            boolean authorized = protocol == Protocol.ANTHROPIC && inference ? authentication.authenticateAnthropic(exchange.getRequest().getHeaders()) : authentication.authenticate(exchange.getRequest().getHeaders());
            if (protectedPath && !authorized) {
                exchange.getAttributes().put(OUTCOME, "AUTHENTICATION_FAILED");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                byte[] body = ProtocolErrors.body(protocol, new GatewayException(GatewayError.AUTHENTICATION_FAILED)).toString().getBytes(StandardCharsets.UTF_8);
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
            return chain.filter(exchange);
        });
        return (inference ? Mono.usingWhen(Mono.just(context), c -> operation,
                c -> finish(exchange, c, exchange.getAttributeOrDefault(OUTCOME, "SUCCESS")),
                (c, error) -> finish(exchange, c, exchange.getAttributeOrDefault(OUTCOME, "INTERNAL_ERROR")),
                c -> finish(exchange, c, "CLIENT_CANCELLED")) : operation)
                .contextWrite(c -> c.put("requestId", id).put(REQUEST_CONTEXT, context));
    }
    private Mono<Void> finish(ServerWebExchange exchange, RequestContext context, String outcome) {
        long nanos = System.nanoTime() - context.startedNanos();
        Usage usage = exchange.getAttribute(USAGE);
        metrics.request(context.protocol(), Boolean.TRUE.equals(exchange.getAttribute(STREAM)), outcome, nanos, usage);
        LOG.atInfo().addKeyValue("requestId", context.requestId()).addKeyValue("sourceProtocol", context.protocol())
                .addKeyValue("stream", Boolean.TRUE.equals(exchange.getAttribute(STREAM))).addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos)).log("gateway_request_finished");
        return dashboard.request(outcome, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos), usage)
                .timeout(java.time.Duration.ofMillis(500)).onErrorResume(ignored -> Mono.empty());
    }
}
