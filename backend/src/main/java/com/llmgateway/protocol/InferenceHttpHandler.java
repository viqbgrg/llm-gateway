package com.llmgateway.protocol;

import com.llmgateway.config.InferenceProperties;
import com.llmgateway.infrastructure.GatewayWebFilter;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import com.llmgateway.protocol.chat.*;
import com.llmgateway.protocol.anthropic.*;
import com.llmgateway.protocol.responses.*;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class InferenceHttpHandler {
    private final InferenceService inference;
    private final InferenceProperties properties;
    private final Clock clock;
    public InferenceHttpHandler(InferenceService inference, InferenceProperties properties, Clock clock) { this.inference = inference; this.properties = properties; this.clock = clock; }
    @Bean public RouterFunction<ServerResponse> inferenceRoutes(ChatCompletionsAdapter chat, AnthropicAdapter anthropic, ResponsesAdapter responses) {
        return route().POST("/v1/chat/completions", r -> handle(r, ChatRequest.class, chat))
                .POST("/v1/messages", r -> handle(r, AnthropicRequest.class, anthropic))
                .POST("/v1/responses", r -> handle(r, ResponsesRequest.class, responses)).build();
    }
    private <I, O> Mono<ServerResponse> handle(ServerRequest http, Class<I> inputType, ClientProtocolAdapter<I, O> adapter) {
        RequestContext context = http.exchange().getAttribute(GatewayWebFilter.REQUEST_CONTEXT);
        return Mono.defer(() -> {
            if (!http.headers().contentType().map(MediaType.APPLICATION_JSON::isCompatibleWith).orElse(false)) return Mono.error(GatewayException.invalid());
            if (context.protocol() == Protocol.ANTHROPIC && !"2023-06-01".equals(http.headers().firstHeader("anthropic-version"))) return Mono.error(GatewayException.invalid());
            if (http.headers().contentLength().orElse(0) > properties.maxRequestBytes()) return Mono.error(new GatewayException(GatewayError.PAYLOAD_TOO_LARGE));
            Duration remaining = Duration.between(clock.instant(), context.enteredAt().plus(properties.deadline()));
            if (remaining.isNegative() || remaining.isZero()) return Mono.error(new GatewayException(GatewayError.TIMEOUT));
            return http.bodyToMono(inputType).switchIfEmpty(Mono.error(GatewayException.invalid())).timeout(remaining).map(input -> adapter.parse(input, context))
                    .flatMap(request -> {
                        http.exchange().getAttributes().put(GatewayWebFilter.STREAM, request.stream());
                        if (!request.stream()) return inference.execute(request, context).flatMap(response -> {
                            if (response.usage() != null) http.exchange().getAttributes().put(GatewayWebFilter.USAGE, response.usage());
                            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(adapter.encode(response));
                        });
                        AtomicReference<String> messageId = new AtomicReference<>();
                        Flux<LlmStreamEvent> source = inference.stream(request, context).doOnNext(event -> {
                            if (event.messageId() != null) messageId.set(event.messageId());
                            if (event instanceof LlmStreamEvent.UsageUpdate u) http.exchange().getAttributes().put(GatewayWebFilter.USAGE, u.usage());
                        });
                        return source.switchOnFirst((first, events) -> {
                            if (first.hasError()) return Flux.error(first.getThrowable());
                            if (!first.hasValue()) return Flux.error(GatewayException.response());
                            Flux<LlmStreamEvent> safe = events.timeout(properties.slowConsumerTimeout()).onErrorResume(failure -> {
                                GatewayException error = error(failure); http.exchange().getAttributes().put(GatewayWebFilter.OUTCOME, error.error().name());
                                return Flux.just(new LlmStreamEvent.Error(messageId.get(), ProtocolErrors.stream(error)));
                            });
                            return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache")
                                    .header("X-Accel-Buffering", "no").body(adapter.encodeStream(safe, request.model()), new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {}).flux();
                        }, false).single();
                    });
        }).onErrorResume(failure -> {
            GatewayException error = error(failure);
            http.exchange().getAttributes().put(GatewayWebFilter.OUTCOME, error.error().name());
            var response = ServerResponse.status(error.error().status()).contentType(MediaType.APPLICATION_JSON);
            if (error.error() == GatewayError.RATE_LIMITED) response.header("Retry-After", Long.toString(Math.max(1, Math.min(60, (error.retryAfter().toMillis() + 999) / 1000))));
            return response.bodyValue(ProtocolErrors.body(context.protocol(), error));
        });
    }
    private GatewayException error(Throwable failure) {
        if (failure instanceof GatewayException known) return known;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataBufferLimitException) return new GatewayException(GatewayError.PAYLOAD_TOO_LARGE);
            if (cause instanceof java.util.concurrent.TimeoutException) return new GatewayException(GatewayError.TIMEOUT);
        }
        return GatewayException.invalid();
    }
}
