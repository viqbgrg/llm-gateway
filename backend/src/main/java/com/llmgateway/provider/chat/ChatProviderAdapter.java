package com.llmgateway.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmgateway.config.InferenceProperties;
import com.llmgateway.infrastructure.credentials.CredentialResolver;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import com.llmgateway.protocol.chat.ChatCompletionsAdapter;
import com.llmgateway.provider.*;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static com.llmgateway.protocol.ProtocolJson.*;

@Component
public class ChatProviderAdapter implements ProviderAdapter {
    private final ProviderTransport transport;
    private final CredentialResolver credentials;
    private final InferenceProperties limits;
    private final Clock clock;
    public ChatProviderAdapter(ProviderTransport transport, CredentialResolver credentials, InferenceProperties limits, Clock clock) {
        this.transport = transport; this.credentials = credentials; this.limits = limits; this.clock = clock;
    }
    @Override public Mono<LlmResponse> execute(LlmRequest request, ProviderContext context) {
        return credentials.resolve(context.provider().id()).flatMap(key -> Mono.defer(() -> {
            var payload = ChatCompletionsAdapter.outbound(request, context.actualModel());
            context.onDispatch().run();
            return transport.client(context.provider(), limits.maxResponseBytes())
                .post().uri(ProviderTransport.endpoint(context.provider().baseUrl(), "chat/completions"))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .headers(h -> ProviderTransport.authenticate(h, context.provider().protocol(), key))
                .bodyValue(payload)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(String.class).switchIfEmpty(Mono.error(GatewayException.response()))
                        : response.releaseBody().then(Mono.error(httpError(response))))
                .map(body -> decode(body, request.model()));
        }))
                .timeout(timeout(context)).onErrorMap(this::transportError);
    }
    @Override public Flux<LlmStreamEvent> stream(LlmRequest request, ProviderContext context) {
        return credentials.resolve(context.provider().id()).flatMapMany(key -> Flux.defer(() -> {
            var payload = ChatCompletionsAdapter.outbound(request, context.actualModel());
            context.onDispatch().run();
            return transport.client(context.provider(), limits.maxSseEventBytes())
                .post().uri(ProviderTransport.endpoint(context.provider().baseUrl(), "chat/completions"))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> ProviderTransport.authenticate(h, context.provider().protocol(), key))
                .bodyValue(payload)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().thenMany(Flux.error(httpError(response)));
                    if (!response.headers().contentType().map(type -> MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)).orElse(false)) {
                        return response.releaseBody().thenMany(Flux.error(GatewayException.response()));
                    }
                    return ChatSseDecoder.decode(SseFramer.decode(response.bodyToFlux(DataBuffer.class), limits.maxSseEventBytes(), context.onTransportEvent()));
                });
        })).timeout(context.provider().readTimeout()).takeUntilOther(Mono.delay(timeout(context)).flatMap(t -> Mono.error(new GatewayException(GatewayError.TIMEOUT))))
                .onErrorMap(this::transportError);
    }
    private Duration timeout(ProviderContext context) {
        return context.remaining().compareTo(context.provider().requestTimeout()) < 0 ? context.remaining() : context.provider().requestTimeout();
    }
    public LlmResponse decode(String body, String logicalModel) {
        try {
            JsonNode root = read(body);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() != 1) throw GatewayException.response();
            JsonNode choice = choices.get(0);
            if (!choice.path("index").isIntegralNumber() || choice.get("index").intValue() != 0) throw GatewayException.response();
            JsonNode message = choice.get("message");
            if (message == null || !"assistant".equals(text(message, "role"))) throw GatewayException.response();
            if (message.hasNonNull("refusal") || message.hasNonNull("reasoning_content") || message.hasNonNull("audio") || message.hasNonNull("function_call")) throw GatewayException.response();
            var blocks = new ArrayList<ContentBlock>();
            if (message.hasNonNull("content")) blocks.add(ContentBlock.text(text(message, "content")));
            if (message.hasNonNull("tool_calls")) {
                if (!message.get("tool_calls").isArray()) throw GatewayException.response();
                for (JsonNode call : message.get("tool_calls")) {
                    if (!"function".equals(text(call, "type"))) throw GatewayException.response();
                    JsonNode function = call.get("function");
                    blocks.add(new ToolCall(text(call, "id"), text(function, "name"), arguments(text(function, "arguments"))));
                }
            }
            return new LlmResponse(text(root, "id"), logicalModel, blocks, finish(text(choice, "finish_reason")), usage(root.get("usage")));
        } catch (Exception ignored) { throw GatewayException.response(); }
    }
    private GatewayException httpError(ClientResponse response) {
        GatewayError error = switch (response.statusCode().value()) {
            case 400, 422 -> GatewayError.PROVIDER_REQUEST_REJECTED;
            case 401, 403 -> GatewayError.PROVIDER_AUTHENTICATION;
            case 404 -> GatewayError.PROVIDER_MODEL_NOT_FOUND;
            case 429 -> GatewayError.RATE_LIMITED;
            default -> GatewayError.PROVIDER_ERROR;
        };
        Duration retryAfter = Duration.ZERO;
        String hint = response.headers().asHttpHeaders().getFirst("Retry-After");
        if (hint != null) {
            try { retryAfter = Duration.ofSeconds(Math.min(60, Math.max(0, Long.parseLong(hint)))); }
            catch (Exception ignored) {
                try { retryAfter = Duration.ofMillis(Math.min(60_000, Math.max(0,
                        ZonedDateTime.parse(hint, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock.millis()))); }
                catch (Exception invalid) { retryAfter = Duration.ZERO; }
            }
        }
        return new GatewayException(error, true, retryAfter);
    }
    private Throwable transportError(Throwable error) {
        if (error instanceof GatewayException) return error;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof ConnectTimeoutException) {
                return new GatewayException(GatewayError.CONNECTION_FAILED, false, Duration.ZERO);
            }
            if (cause instanceof TimeoutException || cause instanceof ReadTimeoutException) return new GatewayException(GatewayError.TIMEOUT);
        }
        if (error instanceof WebClientRequestException) return new GatewayException(GatewayError.NETWORK_ERROR);
        return GatewayException.response();
    }
}
