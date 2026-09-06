package com.llmgateway.discovery;

import com.llmgateway.model.Protocol;
import com.llmgateway.model.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderModelDiscoveryTest {
    private final HttpProviderModelDiscovery discovery = new HttpProviderModelDiscovery(WebClient.builder());
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.disposeNow();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void readsCatalogUsingTheProvidersAuthenticationAndVersionedBaseUrl(Protocol protocol) {
        var path = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var apiKey = new AtomicReference<String>();
        var version = new AtomicReference<String>();
        start((request, response) -> {
            path.set(request.uri());
            authorization.set(request.requestHeaders().get("Authorization"));
            apiKey.set(request.requestHeaders().get("x-api-key"));
            version.set(request.requestHeaders().get("anthropic-version"));
            return json(response, """
                    {"data":[{"id":"small-model","display_name":"Small model","owned_by":"fixture"}]}
                    """);
        });

        StepVerifier.create(discovery.discover(provider(protocol, "/v1/", null, Duration.ofSeconds(2))))
                .assertNext(models -> {
                    assertThat(models).hasSize(1);
                    assertThat(models.getFirst().modelName()).isEqualTo("small-model");
                    assertThat(models.getFirst().displayName()).isEqualTo("Small model");
                    assertThat(models.getFirst().capabilities().values()).isEmpty();
                    assertThat(models.getFirst().rawMetadata().get("owned_by").asText()).isEqualTo("fixture");
                }).verifyComplete();
        assertThat(path.get()).isEqualTo("/v1/models");
        if (protocol == Protocol.ANTHROPIC) {
            assertThat(authorization.get()).isNull();
            assertThat(apiKey.get()).isEqualTo("fixture-provider-key");
            assertThat(version.get()).isEqualTo("2023-06-01");
        } else {
            assertThat(authorization.get()).isEqualTo("Bearer fixture-provider-key");
            assertThat(apiKey.get()).isNull();
        }
    }

    @Test
    void appendsModelsPathToAnUnversionedBaseUrl() {
        var path = new AtomicReference<String>();
        start((request, response) -> {
            path.set(request.uri());
            return json(response, "{\"data\":[]}");
        });
        StepVerifier.create(discovery.discover(provider(Protocol.CHAT_COMPLETIONS, "", null, Duration.ofSeconds(2))))
                .assertNext(models -> assertThat(models).isEmpty()).verifyComplete();
        assertThat(path.get()).isEqualTo("/v1/models");
    }

    @Test
    void usesTheCatalogOverrideAndCollectsAllPagesWithoutDuplicates() {
        var calls = new AtomicInteger();
        var secondPath = new AtomicReference<String>();
        start((request, response) -> {
            if (calls.incrementAndGet() == 1) {
                assertThat(request.uri()).isEqualTo("/catalog?region=test");
                return json(response, """
                        {"data":[{"id":"first"}],"has_more":true,"last_id":"first&cursor"}
                        """);
            }
            secondPath.set(request.uri());
            return json(response, """
                    {"data":[{"id":"first"},{"id":"second"}],"has_more":false}
                    """);
        });
        StepVerifier.create(discovery.discover(provider(Protocol.RESPONSES, "/unused", url("/catalog?region=test"), Duration.ofSeconds(2))))
                .assertNext(models -> assertThat(models).extracting(DiscoveredModel::modelName).containsExactly("first", "second"))
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(secondPath.get()).isEqualTo("/catalog?region=test&after_id=first%26cursor");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"data\":null}", "{\"data\":{}}", "{\"data\":[{}]}",
            "{\"data\":[{\"id\":\"\"}]}", "{\"data\":[{\"id\":1}]}", "{\"data\":[{\"id\":\"valid\"},{}]}",
            "{\"data\":[],\"has_more\":true}", "{\"data\":[],\"has_more\":\"true\"}"})
    void rejectsMalformedCatalogsWithoutReturningPartialModels(String body) {
        start((request, response) -> json(response, body));
        StepVerifier.create(discovery.discover(provider(Protocol.CHAT_COMPLETIONS, "", null, Duration.ofSeconds(2))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderAccessException.class);
                    assertThat(((ProviderAccessException) error).code()).isEqualTo("INVALID_MODEL_CATALOG");
                }).verify();
    }

    @Test
    void rejectsRepeatedPaginationCursors() {
        start((request, response) -> json(response, """
                {"data":[{"id":"first"}],"has_more":true,"last_id":"first"}
                """));
        StepVerifier.create(discovery.discover(provider(Protocol.ANTHROPIC, "", null, Duration.ofSeconds(2))))
                .expectErrorSatisfies(error -> assertThat(((ProviderAccessException) error).code()).isEqualTo("INVALID_MODEL_CATALOG"))
                .verify();
    }

    @Test
    void mapsAuthenticationFailureWithoutExposingTheUpstreamBody() {
        start((request, response) -> json(response.status(401), "{\"error\":\"fixture-provider-key\"}"));
        StepVerifier.create(discovery.discover(provider(Protocol.CHAT_COMPLETIONS, "", null, Duration.ofSeconds(2))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderAccessException.class);
                    ProviderAccessException failure = (ProviderAccessException) error;
                    assertThat(failure.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.code()).isEqualTo("PROVIDER_AUTHENTICATION_FAILED");
                    assertThat(failure.getMessage()).doesNotContain("fixture-provider-key", "127.0.0.1");
                    assertThat(failure.getCause()).isNull();
                }).verify();
    }

    @Test
    void boundsTheEntireRequestWithTheConfiguredTimeout() {
        start((request, response) -> response.sendString(Mono.delay(Duration.ofSeconds(2)).map(ignored -> "{\"data\":[]}")));
        StepVerifier.create(discovery.discover(provider(Protocol.CHAT_COMPLETIONS, "", null, Duration.ofMillis(100))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderAccessException.class);
                    assertThat(((ProviderAccessException) error).status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                }).verify(Duration.ofSeconds(3));
    }

    private void start(BiFunction<HttpServerRequest, HttpServerResponse, ? extends Publisher<Void>> handler) {
        server = HttpServer.create().host("127.0.0.1").port(0).handle(handler).bindNow();
    }

    private Publisher<Void> json(HttpServerResponse response, String body) {
        return response.header("Content-Type", "application/json").sendString(Mono.just(body));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private Provider provider(Protocol protocol, String path, String override, Duration timeout) {
        return new Provider("fixture-provider", "Fixture", url(path), "fixture-provider-key", true, protocol,
                Duration.ofSeconds(1), Duration.ofSeconds(2), timeout, 0, false, override,
                Duration.ofMinutes(30), Instant.now(), Instant.now());
    }
}
