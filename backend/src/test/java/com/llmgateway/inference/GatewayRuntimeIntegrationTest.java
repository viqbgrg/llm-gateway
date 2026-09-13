package com.llmgateway.inference;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.admin.*;
import com.llmgateway.discovery.*;
import com.llmgateway.health.*;
import com.llmgateway.infrastructure.*;
import com.llmgateway.infrastructure.credentials.CredentialService;
import com.llmgateway.model.*;
import com.llmgateway.protocol.ProtocolJson;
import com.llmgateway.resilience.*;
import com.llmgateway.routing.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.api-key=fixture-gateway-key", "gateway.discovery.enabled=false", "spring.flyway.enabled=true",
        "gateway.credentials.encrypted-writes=true", "gateway.credentials.active-key-id=fixture", "spring.data.redis.timeout=500ms",
        "gateway.inference.max-request-bytes=65536", "gateway.inference.max-response-bytes=65536",
        "gateway.inference.max-sse-event-bytes=16384"
})
@AutoConfigureWebTestClient
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Testcontainers
class GatewayRuntimeIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("runtime_test").withUsername("runtime_test").withPassword("synthetic-database-key");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.r2dbc.url", () -> "r2dbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName());
        properties.add("spring.r2dbc.username", MYSQL::getUsername); properties.add("spring.r2dbc.password", MYSQL::getPassword);
        properties.add("spring.flyway.url", MYSQL::getJdbcUrl); properties.add("spring.flyway.user", MYSQL::getUsername); properties.add("spring.flyway.password", MYSQL::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost); properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("gateway.credentials.keyring-file", SyntheticKeyring::file);
    }
    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired ProviderRepository providers;
    @Autowired ProviderModelRepository providerModels;
    @Autowired CredentialService credentials;
    @Autowired DiscoveryCoordinator discovery;
    @Autowired DiscoveryReconciler reconciler;
    @Autowired DiscoveryLeaseService leases;
    @Autowired HealthManager health;
    @Autowired CircuitBreaker circuit;
    @Autowired RedisKeyNamespace keys;
    @Autowired MeterRegistry meters;
    @Autowired ConfigurationStore configurations;
    @Autowired PreferredBindingStore preferences;
    @Autowired DiscoveryStatusStore discoveryStatuses;
    @Autowired RuntimeStateCleanup runtimeCleanup;
    @Autowired DefaultModelRouter router;
    @Autowired org.springframework.transaction.ReactiveTransactionManager transactions;
    @Autowired com.llmgateway.config.DiscoveryProperties discoveryProperties;
    private WebTestClient api;
    private DisposableServer upstream;
    private final List<Captured> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final Map<String, String> responseOverrides = new ConcurrentHashMap<>();
    private final Map<String, Duration> streamTails = new ConcurrentHashMap<>();
    private final AtomicInteger cancellations = new AtomicInteger();
    private volatile String catalog = "{\"data\":[{\"id\":\"real-model\"}]}";

    @BeforeEach void reset() {
        api = client.mutate().defaultHeaders(h -> h.setBearerAuth("fixture-gateway-key")).responseTimeout(Duration.ofSeconds(10)).build();
        database.sql("DELETE FROM virtual_model_bindings").then().then(database.sql("DELETE FROM model_rules").then())
                .then(database.sql("DELETE FROM provider_models").then()).then(database.sql("DELETE FROM virtual_models").then())
                .then(database.sql("DELETE FROM routing_policies").then()).then(database.sql("DELETE FROM providers").then()).block();
        redis.execute(connection -> connection.serverCommands().flushDb()).then().block();
        upstream = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> request.receive().aggregate().asString().defaultIfEmpty("")
                .flatMap(body -> {
                    String path = request.uri(); JsonNode input = body.isEmpty() ? mapper.createObjectNode() : ProtocolJson.read(body);
                    calls.add(new Captured(path, request.requestHeaders().get("Authorization"), input));
                    int status = statuses.getOrDefault(path, 200);
                    response.status(status);
                    Duration delay = delays.getOrDefault(path, Duration.ZERO);
                    if (path.endsWith("/models")) return response.header("Content-Type", "application/json").sendString(Mono.just(catalog).delayElement(delay)).then();
                    if (status != 200) return response.header("Content-Type", "application/json").sendString(Mono.just("{\"secret\":\"fixture-provider-key\"}")).then();
                    if (input.path("stream").asBoolean()) {
                        String id = "s" + path.replace("/", "_");
                        Flux<String> stream = Flux.just(frame(id, "{\"role\":\"assistant\"}", null))
                                .concatWith(Flux.just(frame(id, "{\"content\":\"" + path + "\"}", null), frame(id, "{}", "stop"), "data: [DONE]\n\n").delaySubscription(delay))
                                .doOnCancel(cancellations::incrementAndGet);
                        if (streamTails.containsKey(path)) stream = Flux.just(frame(id, "{\"content\":\"prefix\"}", null))
                                .concatWith(Flux.just(frame(id, "{}", "stop"), "data: [DONE]\n\n").delaySubscription(streamTails.get(path))).doOnCancel(cancellations::incrementAndGet);
                        if (responseOverrides.containsKey(path)) stream = Flux.just(responseOverrides.get(path));
                        return response.header("Content-Type", "text/event-stream").sendString(stream).then();
                    }
                    String result = responseOverrides.getOrDefault(path, "{\"id\":\"synthetic-response\",\"model\":\"real-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + path + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}");
                    return response.header("Content-Type", "application/json").sendString(Mono.just(result).delayElement(delay).doOnCancel(cancellations::incrementAndGet)).then();
                })).bindNow();
    }
    @AfterEach void stop() { if (upstream != null) upstream.disposeNow(); }
    @ParameterizedTest @EnumSource(Protocol.class)
    void runsAllClientProtocolsThroughRealHttpWithLogicalNamesAndEncryptedCredentials(Protocol protocol) {
        Graph graph = graph("public", "a", protocol, null, 0);
        JsonNode result = infer(protocol, "public", false, 200);
        assertThat(result.get("model").asText()).isEqualTo("public");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().authorization()).isEqualTo("Bearer fixture-provider-key");
        assertThat(calls.getFirst().body().get("model").asText()).isEqualTo("real-model");
        ProviderEntity saved = providers.findById(graph.provider()).block();
        assertThat(saved.apiKey()).isNull(); assertThat(saved.apiKeyCiphertext()).startsWith("v1.fixture.");
        assertThat(get("/providers/" + graph.provider()).toString()).doesNotContain("fixture-provider-key");
    }
    @ParameterizedTest @EnumSource(Protocol.class)
    void streamsACompleteResponseForEveryProtocol(Protocol protocol) {
        graph("stream", "a", protocol, null, 0);
        String body = stream(protocol, "stream");
        assertThat(body).contains("/a/v1/chat/completions");
        assertThat(body).contains(protocol == Protocol.CHAT_COMPLETIONS ? "[DONE]" : protocol == Protocol.ANTHROPIC ? "message_stop" : "response.completed");
        assertThat(calls).hasSize(1);
    }
    @ParameterizedTest @EnumSource(Protocol.class)
    void preservesToolAndImageConversationsOverHttpAndRejectsMissingCapabilities(Protocol protocol) throws Exception {
        Graph graph = graph("public-model", "a", protocol, null, 0);
        JsonNode example = ProtocolJson.read(fixture("manifest.json")).get("conversationCases").get(protocol.ordinal());
        JsonNode request = ProtocolJson.read(fixture(example.get("request").asText()));
        responseOverrides.put("/a/v1/chat/completions", fixture(example.get("upstreamResponse").asText()));
        ObjectNode response = (ObjectNode) api.post().uri(endpoint(protocol)).header("anthropic-version", "2023-06-01")
                .bodyValue(request).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        if (protocol == Protocol.CHAT_COMPLETIONS) response.put("created", 0);
        if (protocol == Protocol.RESPONSES) response.put("created_at", 0);
        assertThat(response).isEqualTo(ProtocolJson.read(fixture(example.get("clientResponse").asText())));
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().body()).isEqualTo(ProtocolJson.read(fixture(example.get("upstreamRequest").asText())));

        api.put().uri("/api/admin/bindings/" + graph.binding())
                .bodyValue(((ObjectNode) get("/bindings/" + graph.binding())).put("capabilitiesOverride", "[\"CHAT\"]"))
                .exchange().expectStatus().isOk();
        api.post().uri(endpoint(protocol)).header("anthropic-version", "2023-06-01").bodyValue(request)
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.code").isEqualTo("capability_unsupported")
                .jsonPath("$.error.missing_capabilities").value(value -> assertThat((List<?>) value).isEqualTo(List.of("TOOLS", "VISION")));
        assertThat(calls).hasSize(1);
    }
    @ParameterizedTest @EnumSource(value = Protocol.class, names = {"CHAT_COMPLETIONS", "RESPONSES"})
    void preservesStructuredOutputConstraintsAndEnforcesTheCapabilityAtHttpBoundary(Protocol protocol) {
        Graph graph = graph("structured", "a", protocol, null, 0);
        JsonNode schema = ProtocolJson.read("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"}},\"required\":[\"value\"],\"additionalProperties\":false}");
        ObjectNode definition = mapper.createObjectNode().put("name", "synthetic_result").put("strict", true).set("schema", schema);
        ObjectNode format = mapper.createObjectNode().put("type", "json_schema").set("json_schema", definition);
        ObjectNode body = request(protocol, "structured", false);
        if (protocol == Protocol.CHAT_COMPLETIONS) body.set("response_format", format);
        else body.set("text", mapper.createObjectNode().set("format", definition.deepCopy().put("type", "json_schema")));
        ObjectNode upstreamResponse = (ObjectNode) ProtocolJson.read("{\"id\":\"structured-response\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"},\"finish_reason\":\"stop\"}]}");
        ((ObjectNode) upstreamResponse.at("/choices/0/message")).put("content", "{\"value\":2}");
        responseOverrides.put("/a/v1/chat/completions", upstreamResponse.toString());
        JsonNode response = api.post().uri(endpoint(protocol)).bodyValue(body).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        String content = response.at(protocol == Protocol.CHAT_COMPLETIONS ? "/choices/0/message/content" : "/output/0/content/0/text").asText();
        assertThat(ProtocolJson.read(content)).isEqualTo(ProtocolJson.read("{\"value\":2}"));
        assertThat(calls.getFirst().body().get("response_format")).isEqualTo(format);
        api.put().uri("/api/admin/bindings/" + graph.binding())
                .bodyValue(((ObjectNode) get("/bindings/" + graph.binding())).put("capabilitiesOverride", "[\"CHAT\"]"))
                .exchange().expectStatus().isOk();
        api.post().uri(endpoint(protocol)).bodyValue(body).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.code").isEqualTo("capability_unsupported")
                .jsonPath("$.error.missing_capabilities[0]").isEqualTo("STRUCTURED_OUTPUT");
        assertThat(calls).hasSize(1);
    }
    @Test void rejectsEveryUnsupportedFixtureOverHttpWithoutDispatching() throws Exception {
        Graph graph = graph("public-model", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        extra(graph.virtual(), "b", Protocol.ANTHROPIC, 0, 0);
        extra(graph.virtual(), "c", Protocol.RESPONSES, 0, 0);
        for (JsonNode example : ProtocolJson.read(fixture("manifest.json")).get("rejections")) {
            Protocol protocol = Protocol.valueOf(example.get("protocol").asText());
            api.post().uri(endpoint(protocol)).header("anthropic-version", "2023-06-01").bodyValue(example.get("request"))
                    .exchange().expectStatus().isBadRequest().expectBody()
                    .jsonPath("$.error.code").isEqualTo(example.get("code").asText().toLowerCase(Locale.ROOT));
        }
        assertThat(calls).isEmpty();
    }
    @ParameterizedTest @EnumSource(Protocol.class)
    void rejectsDeclaredAndChunkedOversizedRequestsBeforeDispatch(Protocol protocol) {
        graph("limits", "a", protocol, null, 0);
        ObjectNode body = request(protocol, "limits", false);
        String prompt = "synthetic-size-limit-".repeat(4000);
        if (protocol == Protocol.RESPONSES) body.put("input", prompt);
        else ((ObjectNode) body.at("/messages/0")).put("content", prompt);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isGreaterThan(65536);
        api.post().uri(endpoint(protocol)).header("anthropic-version", "2023-06-01").contentType(MediaType.APPLICATION_JSON)
                .contentLength(bytes.length).bodyValue(bytes).exchange().expectStatus().isEqualTo(413).expectBody()
                .jsonPath("$.error.code").isEqualTo("payload_too_large");
        Flux<DataBuffer> chunks = Flux.range(0, (bytes.length + 8191) / 8192).map(index -> DefaultDataBufferFactory.sharedInstance
                .wrap(Arrays.copyOfRange(bytes, index * 8192, Math.min(bytes.length, (index + 1) * 8192))));
        api.post().uri(endpoint(protocol)).header("anthropic-version", "2023-06-01").contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromDataBuffers(chunks)).exchange().expectStatus().isEqualTo(413).expectBody()
                .jsonPath("$.error.code").isEqualTo("payload_too_large");
        assertThat(calls).isEmpty();
    }
    @ParameterizedTest @EnumSource(Protocol.class)
    void rejectsOversizedUpstreamBodiesAndSseFramesWithSafeErrors(Protocol protocol) throws Exception {
        graph("limits", "a", protocol, null, 0);
        String content = "synthetic-upstream-size-marker-".repeat(3000);
        ObjectNode response = (ObjectNode) ProtocolJson.read(fixture("chat-response.json"));
        ((ObjectNode) response.at("/choices/0/message")).put("content", content);
        responseOverrides.put("/a/v1/chat/completions", response.toString());
        JsonNode error = infer(protocol, "limits", false, 502);
        assertThat(error.at("/error/code").asText()).isEqualTo("invalid_response");
        assertThat(error.toString()).doesNotContain("synthetic-upstream-size-marker", "fixture-provider-key");
        responseOverrides.put("/a/v1/chat/completions", frame("oversized", mapper.createObjectNode().put("content", content).toString(), null));
        JsonNode streamError = infer(protocol, "limits", true, 502);
        assertThat(streamError.at("/error/code").asText()).isEqualTo("invalid_response");
        assertThat(streamError.toString()).doesNotContain("synthetic-upstream-size-marker", "fixture-provider-key");
        assertThat(calls).hasSize(2);
    }
    @Test void logsOnlySafeLifecycleContextAndSanitizesRequestIds() throws Exception {
        graph("private-model-input", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        String prompt = "synthetic-private-prompt-marker";
        String output = "synthetic-private-output-marker";
        ObjectNode response = (ObjectNode) ProtocolJson.read(fixture("chat-response.json"));
        ((ObjectNode) response.at("/choices/0/message")).put("content", output);
        responseOverrides.put("/a/v1/chat/completions", response.toString());
        ObjectNode body = request(Protocol.CHAT_COMPLETIONS, "private-model-input", false);
        ((ObjectNode) body.at("/messages/0")).put("content", prompt);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = new CopyOnWriteArrayList<>();
        Logger requestLogger = (Logger) org.slf4j.LoggerFactory.getLogger(GatewayWebFilter.class);
        Logger attemptLogger = (Logger) org.slf4j.LoggerFactory.getLogger(GatewayMetrics.class);
        appender.start(); requestLogger.addAppender(appender); attemptLogger.addAppender(appender);
        try {
            api.post().uri("/v1/chat/completions").header("X-Request-ID", "acceptance-request_123").bodyValue(body)
                    .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Request-ID", "acceptance-request_123");
            statuses.put("/a/v1/chat/completions", 401);
            String id = api.post().uri("/v1/chat/completions").header("X-Request-ID", "invalid request/id").bodyValue(body)
                    .exchange().expectStatus().isEqualTo(502).expectBody().returnResult().getResponseHeaders().getFirst("X-Request-ID");
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(appender.list).hasSize(4));
            assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                    .containsExactlyInAnyOrder("gateway_attempt_finished", "gateway_request_finished", "gateway_attempt_finished", "gateway_request_finished");
            String logs = appender.list.stream().map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("acceptance-request_123", id, "requestId", "attemptId", "virtualModelId", "bindingId", "providerId",
                            "sourceProtocol", "targetProtocol", "attemptKind", "outcome", "durationMs", "SUCCESS", "AUTHENTICATION_ERROR", "PROVIDER_ERROR")
                    .doesNotContain(prompt, output, "fixture-provider-key", "fixture-gateway-key", "Authorization", "invalid request/id", "127.0.0.1", "private-model-input");
            meters.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("llm.gateway.")).forEach(meter -> {
                assertThat(meter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                        .isSubsetOf("protocol", "stream", "outcome", "kind", "scope", "direction", "reason", "from", "to", "binding");
                assertThat(meter.getId().toString()).doesNotContain(prompt, output, "private-model-input", "acceptance-request_123", "fixture-provider-key", "fixture-gateway-key");
            });
        } finally {
            requestLogger.detachAppender(appender); attemptLogger.detachAppender(appender); appender.stop();
        }
    }
    @Test void rejectsGatewayCredentialsUnknownParametersAndDisabledExactModelsWithoutCallingProviders() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).bodyValue(request(Protocol.CHAT_COMPLETIONS, "public", false))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.error.code").isEqualTo("authentication_failed");
        var invalid = request(Protocol.CHAT_COMPLETIONS, "public", false).put("logprobs", true);
        api.post().uri("/v1/chat/completions").bodyValue(invalid).exchange().expectStatus().isBadRequest();
        api.put().uri("/api/admin/virtual-models/" + graph.virtual()).bodyValue(mapper.createObjectNode().put("name", "public").put("enabled", false))
                .exchange().expectStatus().isOk();
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 400);
        assertThat(calls).isEmpty();
    }
    @Test void validatesAnthropicVersionAndConflictingAuthenticationHeaders() {
        graph("public", "a", Protocol.ANTHROPIC, null, 0);
        api.post().uri("/v1/messages").header("x-api-key", "fixture-gateway-key").header("anthropic-version", "2023-06-01")
                .bodyValue(request(Protocol.ANTHROPIC, "public", false)).exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.type").isEqualTo("error");
        api.post().uri("/v1/messages").bodyValue(request(Protocol.ANTHROPIC, "public", false)).exchange().expectStatus().isBadRequest();
        client.post().uri("/v1/messages").header("x-api-key", "fixture-gateway-key").header("anthropic-version", "2023-06-01")
                .bodyValue(request(Protocol.ANTHROPIC, "public", false)).exchange().expectStatus().isOk();
        assertThat(calls).hasSize(1);
    }
    @Test void mapsProviderFailuresSafelyAndDoesNotReplayByDefault() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        statuses.put("/a/v1/chat/completions", 401);
        JsonNode error = infer(Protocol.CHAT_COMPLETIONS, "public", false, 502);
        assertThat(error.toString()).doesNotContain("fixture-provider-key").doesNotContain("127.0.0.1");
        assertThat(calls).hasSize(1);
        assertThat(circuit.snapshot(graph.binding()).block().configurationBlocked()).isTrue();
    }
    @Test void retriesThenFallsBackWithinOneGlobalAttemptBudget() {
        String policy = policy(true, false, 0, 3, 800, 8);
        Graph first = graph("public", "a", Protocol.CHAT_COMPLETIONS, policy, 1);
        extra(first.virtual(), "b", Protocol.CHAT_COMPLETIONS, 1, 3);
        statuses.put("/a/v1/chat/completions", 503);
        double attemptsBefore = meters.find("llm.gateway.provider.attempts").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        JsonNode result = infer(Protocol.CHAT_COMPLETIONS, "public", false, 200);
        assertThat(result.toString()).contains("/b/v1/chat/completions");
        assertThat(calls).extracting(Captured::path).containsExactly("/a/v1/chat/completions", "/a/v1/chat/completions", "/b/v1/chat/completions");
        assertThat(meters.find("llm.gateway.provider.attempts").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum() - attemptsBefore).isEqualTo(calls.size());
    }
    @Test void picksTheSecondHedgeAndCancelsTheSlowRealConnection() {
        String policy = policy(true, true, 1, 3, 30, 8);
        Graph first = graph("public", "a", Protocol.CHAT_COMPLETIONS, policy, 0);
        extra(first.virtual(), "b", Protocol.CHAT_COMPLETIONS, 1, 0);
        delays.put("/a/v1/chat/completions", Duration.ofSeconds(3));
        assertThat(infer(Protocol.CHAT_COMPLETIONS, "public", false, 200).toString()).contains("/b/v1/chat/completions");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(cancellations.get()).isPositive());
        assertThat(calls).hasSize(2);
    }
    @Test void aRoleDeltaCannotWinAStreamingHedgeAndTheWinnerIsSubscribedOnlyOnce() {
        String policy = policy(true, true, 1, 3, 40, 8);
        Graph first = graph("public", "a", Protocol.CHAT_COMPLETIONS, policy, 0);
        extra(first.virtual(), "b", Protocol.CHAT_COMPLETIONS, 1, 0);
        delays.put("/a/v1/chat/completions", Duration.ofSeconds(3));
        String body = stream(Protocol.CHAT_COMPLETIONS, "public");
        assertThat(body).contains("/b/v1/chat/completions").doesNotContain("/a/v1/chat/completions");
        assertThat(calls).hasSize(2);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(cancellations.get()).isPositive());
    }
    @Test void aCommittedStreamFailureCannotAppendAnotherProvider() {
        String policy = policy(true, false, 0, 3, 800, 8);
        Graph first = graph("public", "a", Protocol.CHAT_COMPLETIONS, policy, 1);
        extra(first.virtual(), "b", Protocol.CHAT_COMPLETIONS, 1, 0);
        responseOverrides.put("/a/v1/chat/completions", frame("s", "{\"content\":\"prefix\"}", null));
        String body = stream(Protocol.CHAT_COMPLETIONS, "public");
        assertThat(body).contains("prefix").contains("invalid_response").doesNotContain("[DONE]");
        assertThat(calls).hasSize(1);
    }
    @Test void wildcardPreviewDoesNotCallProvidersOrAcquirePermitsAndUpdatesApplyImmediately() {
        Graph graph = graph("target", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        JsonNode rule = create("model-rules", mapper.createObjectNode().put("pattern", "alias-*").put("priority", 0).put("enabled", true).put("virtualModelId", graph.virtual()));
        api.post().uri("/api/admin/routing/preview").bodyValue(mapper.createObjectNode().put("model", "alias-one").put("protocol", "CHAT_COMPLETIONS"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.resolvedModel").isEqualTo("target");
        assertThat(circuit.snapshot(graph.binding()).block().activePermits()).isZero(); assertThat(calls).isEmpty();
        assertThat(infer(Protocol.CHAT_COMPLETIONS, "alias-one", false, 200).get("model").asText()).isEqualTo("alias-one");
        api.put().uri("/api/admin/model-rules/" + rule.get("id").asText()).bodyValue(((ObjectNode) rule).put("enabled", false))
                .exchange().expectStatus().isOk();
        infer(Protocol.CHAT_COMPLETIONS, "alias-two", false, 404);
        assertThat(calls).hasSize(1);
    }
    @Test void ruleConflictsPolicyReferencesAndStaleVersionsAreRejected() {
        String policy = policy(false, false, 0, 3, 800, 8);
        Graph first = graph("first", "a", Protocol.CHAT_COMPLETIONS, policy, 0);
        Graph other = graph("second", "b", Protocol.CHAT_COMPLETIONS, null, 0);
        ObjectNode input = mapper.createObjectNode().put("pattern", "alias-*").put("priority", 1).put("enabled", true).put("virtualModelId", first.virtual());
        JsonNode saved = create("model-rules", input);
        api.post().uri("/api/admin/model-rules").bodyValue(input.put("virtualModelId", other.virtual())).exchange().expectStatus().isEqualTo(409);
        api.put().uri("/api/admin/model-rules/" + saved.get("id").asText()).bodyValue(input.put("version", saved.get("version").asLong() - 1)).exchange().expectStatus().isEqualTo(409);
        api.delete().uri("/api/admin/routing-policies/" + policy).exchange().expectStatus().isEqualTo(409);
        api.delete().uri("/api/admin/virtual-models/" + first.virtual()).exchange().expectStatus().isEqualTo(409);
    }
    @Test void reconcilesMissingAndReappearingModelsWithoutOverwritingManualStateOrIdentity() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        enableDiscovery(graph.provider());
        ProviderModelEntity original = providerModels.findById(graph.model()).block();
        catalog = "{\"data\":[]}";
        discovery.automatic(graph.provider()).block();
        assertThat(providerModels.findById(graph.model()).block().status()).isEqualTo(ProviderModelStatus.ACTIVE);
        discovery.automatic(graph.provider()).block();
        assertThat(providerModels.findById(graph.model()).block().status()).isEqualTo(ProviderModelStatus.REMOVED);
        catalog = "{\"data\":[{\"id\":\"real-model\"}]}";
        discovery.automatic(graph.provider()).block();
        ProviderModelEntity restored = providerModels.findById(graph.model()).block();
        assertThat(restored.status()).isEqualTo(ProviderModelStatus.ACTIVE); assertThat(restored.firstSeenAt()).isEqualTo(original.firstSeenAt());
        assertThat(restored.capabilities()).isEqualTo(original.capabilities());
        var edit = modelBody(graph.provider()).put("status", "DISABLED");
        api.put().uri("/api/admin/provider-models/" + graph.model()).bodyValue(edit).exchange().expectStatus().isOk();
        catalog = "{\"data\":[]}"; discovery.automatic(graph.provider()).block(); discovery.automatic(graph.provider()).block();
        catalog = "{\"data\":[{\"id\":\"real-model\"}]}"; discovery.automatic(graph.provider()).block();
        assertThat(providerModels.findById(graph.model()).block().status()).isEqualTo(ProviderModelStatus.DISABLED);
    }
    @Test void staleGenerationsAndFailedCatalogsCannotRemoveModels() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0); enableDiscovery(graph.provider());
        ProviderEntity p = providers.findById(graph.provider()).block();
        var lease = leases.acquire(p.id(), Duration.ofSeconds(5)).block();
        long old = reconciler.allocate(p.id()).block(); reconciler.allocate(p.id()).block();
        StepVerifier.create(reconciler.apply(p, lease, old, List.of(), true)).expectError(GatewayException.class).verify(); leases.release(lease).block();
        catalog = "{\"data\":[{\"bad\":\"invalid\"}]}";
        StepVerifier.create(discovery.automatic(p.id())).expectError().verify();
        assertThat(providerModels.findById(graph.model()).block().missingCount()).isZero();
    }
    @Test void twoCircuitInstancesShareAtomicHalfOpenPermitsAndCancelDoesNotPunishProviders() {
        var clock = new MutableClock(); var one = new RedisCircuitBreaker(redis, keys, clock); var two = new RedisCircuitBreaker(redis, keys, clock);
        ExecutionPolicy policy = new ExecutionPolicy(60000, 3, false, 0, 0, 0, 2, 1000, 1);
        for (int i = 0; i < 2; i++) {
            var permit = one.acquire("shared", "1:1:1:1:1", "p" + i, Duration.ofSeconds(2), policy).block();
            one.settle(permit, AttemptOutcome.PROVIDER_ERROR, policy, Duration.ZERO).block();
        }
        assertThat(two.snapshot("shared").block().state()).isEqualTo(CircuitState.OPEN);
        clock.now = clock.now.plusSeconds(2);
        var permits = Flux.range(0, 20).flatMap(i -> (i % 2 == 0 ? one : two).acquire("shared", "1:1:1:1:1", "h" + i, Duration.ofSeconds(2), policy).onErrorResume(e -> Mono.empty())).collectList().block();
        assertThat(permits).hasSize(1);
        two.settle(permits.getFirst(), AttemptOutcome.HEDGE_CANCELLED, policy, Duration.ZERO).block();
        var retry = one.acquire("shared", "1:1:1:1:1", "recovery", Duration.ofSeconds(2), policy).block();
        one.settle(retry, AttemptOutcome.SUCCESS, policy, Duration.ZERO).block();
        assertThat(two.snapshot("shared").block().state()).isEqualTo(CircuitState.CLOSED);
    }
    @Test void migrationIsRepeatableAndEncryptedCredentialsAreUsedForDiscoveryAndConnectionTests() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        database.sql("UPDATE providers SET api_key_ciphertext=NULL, api_key='synthetic-legacy-key' WHERE id=:id").bind("id", graph.provider()).then().block();
        assertThat(credentials.migrate(100, false).block().migrated()).isEqualTo(1);
        assertThat(credentials.migrate(100, false).block().remainingLegacy()).isZero();
        assertThat(credentials.resolve(graph.provider()).block()).isEqualTo("synthetic-legacy-key");
        api.post().uri("/api/admin/providers/" + graph.provider() + "/test-connection").exchange().expectStatus().isOk();
        api.post().uri("/api/admin/providers/" + graph.provider() + "/sync-models").exchange().expectStatus().isOk();
        assertThat(calls).allSatisfy(call -> assertThat(call.authorization()).isEqualTo("Bearer synthetic-legacy-key"));
    }
    @Test void exposesBoundedDashboardDataAndProtectsPrometheus() {
        graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0); infer(Protocol.CHAT_COMPLETIONS, "public", false, 200);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(get("/dashboard").get("traffic").get("requests").asLong()).isEqualTo(1));
        assertThat(get("/dashboard").get("traffic").get("knownInputTokens").asLong()).isEqualTo(4);
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        String scrape = api.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape).contains("llm_gateway_requests_total", "llm_gateway_provider_attempts_total", "llm_gateway_tokens_total").doesNotContain("fixture-provider-key");
    }
    @Test void clientDisconnectCancelsTheRealProviderStreamAndReleasesItsPermit() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        streamTails.put("/a/v1/chat/completions", Duration.ofSeconds(10));
        var frames = api.post().uri("/v1/chat/completions").bodyValue(request(Protocol.CHAT_COMPLETIONS, "public", true))
                .exchange().expectStatus().isOk().returnResult(new org.springframework.core.ParameterizedTypeReference<org.springframework.http.codec.ServerSentEvent<String>>() {}).getResponseBody();
        StepVerifier.create(frames.filter(frame -> frame.data() != null && frame.data().contains("prefix"))).expectNextCount(1).thenCancel().verify(Duration.ofSeconds(3));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(cancellations.get()).isPositive(); assertThat(circuit.snapshot(graph.binding()).block().activePermits()).isZero();
            assertThat(health.binding(graph.binding()).block().failureCount()).isZero();
        });
        assertThat(calls).hasSize(1);
    }
    @Test void rejectsUnsupportedCapabilitiesAndMalformedNumbersBeforeDispatch() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        api.put().uri("/api/admin/provider-models/" + graph.model()).bodyValue(modelBody(graph.provider()).put("capabilities", "[\"CHAT\"]")).exchange().expectStatus().isOk();
        JsonNode error = infer(Protocol.CHAT_COMPLETIONS, "public", true, 400);
        assertThat(error.get("error").get("missing_capabilities").toString()).contains("STREAMING");
        api.post().uri("/v1/chat/completions").bodyValue(request(Protocol.CHAT_COMPLETIONS, "public", false).put("max_tokens", 1.5))
                .exchange().expectStatus().isBadRequest();
        api.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"model\":\"public\",\"model\":\"public\",\"messages\":[]}")
                .exchange().expectStatus().isBadRequest();
        assertThat(calls).isEmpty();
    }
    @Test void upstreamTimeoutAndInvalidJsonHaveSafeErrors() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        api.put().uri("/api/admin/providers/" + graph.provider()).bodyValue(((ObjectNode) get("/providers/" + graph.provider())).put("requestTimeoutMs", 100))
                .exchange().expectStatus().isOk();
        delays.put("/a/v1/chat/completions", Duration.ofSeconds(2));
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 504);
        delays.clear(); responseOverrides.put("/a/v1/chat/completions", "{\"fixture-provider-key\": invalid}");
        JsonNode failure = infer(Protocol.CHAT_COMPLETIONS, "public", false, 502);
        assertThat(failure.toString()).doesNotContain("fixture-provider-key");
        assertThat(calls).hasSize(2);
    }
    @Test void redisFailurePreventsNewAttemptsAndRecoversWithoutRestart() throws Exception {
        graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "1500", "ALL");
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 503);
        assertThat(calls).isEmpty();
        await().atMost(Duration.ofSeconds(3)).ignoreExceptions().until(() -> redis.hasKey("probe").block(Duration.ofSeconds(1)) != null);
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 200);
        assertThat(calls).hasSize(1);
    }
    @Test void catalogMetadataUpdatesCannotResetAnInferenceCircuit() {
        String policy = policy(false, false, 0, 3, 800, 1);
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, policy, 0); enableDiscovery(graph.provider());
        statuses.put("/a/v1/chat/completions", 503);
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 502);
        CircuitSnapshot before = circuit.snapshot(graph.binding()).block();
        long routingVersion = providerModels.findById(graph.model()).block().routingVersion();
        discovery.automatic(graph.provider()).block();
        assertThat(providerModels.findById(graph.model()).block().routingVersion()).isEqualTo(routingVersion);
        assertThat(circuit.snapshot(graph.binding()).block().configurationVersion()).isEqualTo(before.configurationVersion());
        assertThat(circuit.snapshot(graph.binding()).block().state()).isEqualTo(CircuitState.OPEN);
    }
    @Test void twoDiscoveryCoordinatorsUseOneLeaseAndGenerationsNeverGoBackwards() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0); enableDiscovery(graph.provider());
        var another = new DiscoveryCoordinator(providers, new HttpProviderModelDiscovery(org.springframework.web.reactive.function.client.WebClient.builder()), credentials,
                new DiscoveryLeaseService(redis, keys), reconciler, discoveryStatuses, discoveryProperties, Clock.systemUTC(),
                new GatewayMetrics(meters), runtimeCleanup);
        delays.put("/a/v1/models", Duration.ofMillis(100));
        var results = Flux.merge(discovery.automatic(graph.provider()), another.automatic(graph.provider())).collectList().block();
        assertThat(results).hasSize(1); assertThat(calls).hasSize(1);
        long first = results.getFirst().generation();
        redis.delete(keys.discoveryLease(graph.provider())).block();
        assertThat(discovery.automatic(graph.provider()).block().generation()).isGreaterThan(first);
    }
    @Test void healthUpdatesAreAtomicAcrossInstancesAndStreamingLatencyUsesASeparateSampleSet() {
        var clock = new MutableClock(); var first = new RedisHealthManager(redis, keys, clock); var second = new RedisHealthManager(redis, keys, clock);
        Flux.range(0, 40).flatMap(i -> (i % 2 == 0 ? first : second).record("binding", "provider", "model", AttemptOutcome.SUCCESS,
                i < 20 ? 100 : 2000, i < 20 ? null : 10L, i >= 20, 60_000)).then().block();
        first.record("binding", "provider", "model", AttemptOutcome.HEDGE_CANCELLED, 1, null, true, 60_000).block();
        HealthSnapshot snapshot = second.binding("binding").block();
        assertThat(snapshot.successCount()).isEqualTo(40); assertThat(snapshot.failureCount()).isZero();
        assertThat(snapshot.averageLatencyMs()).isEqualTo(100); assertThat(snapshot.averageTtftMs()).isEqualTo(10);
        assertThat(snapshot.nonStreamingSamples()).isEqualTo(20); assertThat(snapshot.cancelledCount()).isEqualTo(1);
        clock.now = clock.now.plusSeconds(901);
        assertThat(first.binding("binding").block().status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(first.binding("binding").block().expired()).isTrue();
    }
    @Test void preferencesHaveAHoldTimeRejectOldAttemptsAndExpireAcrossInstances() {
        var clock = new MutableClock(); var first = new PreferredBindingStore(redis, keys, clock); var second = new PreferredBindingStore(redis, keys, clock);
        AdaptivePolicy policy = AdaptivePolicy.defaults();
        first.update("vm", "a", "1:1:1:1:1", 0.6, policy, 100).block();
        second.update("vm", "b", "1:1:1:1:1", 0.9, policy, 101).block();
        assertThat(first.read("vm").block().bindingId()).isEqualTo("a");
        clock.now = clock.now.plusSeconds(31);
        second.update("vm", "b", "1:1:1:1:1", 0.9, policy, 102).block();
        assertThat(first.read("vm").block().bindingId()).isEqualTo("b");
        clock.now = clock.now.plusSeconds(31);
        first.update("vm", "a", "1:1:1:1:1", 2, policy, 99).block();
        assertThat(second.read("vm").block().bindingId()).isEqualTo("b");
        redis.expire(keys.preferredVirtualModel("vm"), Duration.ofMillis(20)).block();
        await().atMost(Duration.ofSeconds(2)).until(() -> second.read("vm").block() == null);
    }
    @Test void firstTransportEventAndFirstContentHaveSeparateMetrics() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        double eventBefore = totalTime("llm.gateway.provider.first.event");
        double ttftBefore = totalTime("llm.gateway.provider.ttft");
        delays.put("/a/v1/chat/completions", Duration.ofMillis(250));
        stream(Protocol.CHAT_COMPLETIONS, "public");
        double event = totalTime("llm.gateway.provider.first.event") - eventBefore;
        double ttft = totalTime("llm.gateway.provider.ttft") - ttftBefore;
        assertThat(event).isPositive(); assertThat(ttft).isGreaterThanOrEqualTo(event);
        assertThat(ttft).isGreaterThan(0.2);
        assertThat(health.binding(graph.binding()).block().ttftSamples()).isEqualTo(1);
    }
    @Test void capabilitySpecificRequestsSkipThePreferenceWithoutDeletingItAndPolicyChangesInvalidateIt() {
        ObjectNode policy = mapper.createObjectNode().put("name", "adaptive").put("strategy", "ADAPTIVE").put("hedgeDelayMs", 800);
        JsonNode savedPolicy = create("routing-policies", policy);
        Graph preferred = graph("public", "a", Protocol.CHAT_COMPLETIONS, savedPolicy.get("id").asText(), 0);
        Graph tools = extra(preferred.virtual(), "b", Protocol.CHAT_COMPLETIONS, 1, 0);
        api.put().uri("/api/admin/provider-models/" + preferred.model()).bodyValue(modelBody(preferred.provider()).put("capabilities", "[\"CHAT\"]"))
                .exchange().expectStatus().isOk();
        ConfigurationSnapshot snapshot = configurations.load("public").block();
        RoutingCandidate candidate = snapshot.candidates().stream().filter(c -> c.binding().id().equals(preferred.binding())).findFirst().orElseThrow();
        String version = snapshot.fingerprint(candidate);
        preferences.update(preferred.virtual(), preferred.binding(), version, 0.8, snapshot.policy().adaptive(), System.currentTimeMillis()).block();
        assertThat(router.preview("public", Protocol.CHAT_COMPLETIONS, Set.of(ModelCapability.CHAT, ModelCapability.TOOLS)).block().candidateBindings())
                .extracting(c -> c.binding().id()).containsExactly(tools.binding());
        assertThat(preferences.read(preferred.virtual()).block().bindingId()).isEqualTo(preferred.binding());
        assertThat(router.preview("public", Protocol.CHAT_COMPLETIONS, Set.of(ModelCapability.CHAT)).block().candidateBindings().getFirst().binding().id()).isEqualTo(preferred.binding());
        assertThat(get("/health/bindings/" + preferred.binding()).get("preferred").asBoolean()).isTrue();
        api.put().uri("/api/admin/routing-policies/" + savedPolicy.get("id").asText()).bodyValue(policy.put("version", savedPolicy.get("version").asLong()))
                .exchange().expectStatus().isOk();
        assertThat(preferences.read(preferred.virtual()).block()).isNull();
        assertThat(configurations.current("public", snapshot, candidate).block()).isFalse();
        preferences.update(preferred.virtual(), preferred.binding(), version, 1, snapshot.policy().adaptive(), System.currentTimeMillis()).block();
        assertThat(get("/health/bindings/" + preferred.binding()).get("preferred").asBoolean()).isFalse();
    }
    @Test void healthAndDashboardExcludeDisabledModelsProvidersAndCredentialBlocks() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        assertThat(get("/dashboard").get("availableBindings").asLong()).isEqualTo(1);
        statuses.put("/a/v1/chat/completions", 401); infer(Protocol.CHAT_COMPLETIONS, "public", false, 502);
        assertThat(get("/dashboard").get("availableBindings").asLong()).isZero();
        assertThat(get("/health/bindings/" + graph.binding()).get("availabilityReason").asText()).isEqualTo("CONFIGURATION_BLOCKED");
        api.put().uri("/api/admin/providers/" + graph.provider()).bodyValue(((ObjectNode) get("/providers/" + graph.provider())).put("enabled", false)).exchange().expectStatus().isOk();
        assertThat(get("/health/bindings/" + graph.binding()).get("availabilityReason").asText()).isEqualTo("PROVIDER_DISABLED");
        api.put().uri("/api/admin/providers/" + graph.provider()).bodyValue(((ObjectNode) get("/providers/" + graph.provider())).put("enabled", true)).exchange().expectStatus().isOk();
        api.put().uri("/api/admin/provider-models/" + graph.model()).bodyValue(modelBody(graph.provider()).put("status", "DISABLED")).exchange().expectStatus().isOk();
        assertThat(get("/dashboard").get("availableBindings").asLong()).isZero();
        assertThat(get("/health/bindings/" + graph.binding()).get("availabilityReason").asText()).isEqualTo("MODEL_DISABLED");
    }
    @Test void legacyIncompatibleCapabilitiesHaveARepairListAndCannotReachProviders() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        database.sql("UPDATE provider_models SET capabilities='[\"synthetic-invalid-capability\"]' WHERE id=:id").bind("id", graph.model()).then().block();
        JsonNode issues = get("/routing/validation");
        assertThat(issues.toString()).contains(graph.model(), graph.binding(), "INVALID_CAPABILITIES").doesNotContain("synthetic-invalid-capability", "fixture-provider-key");
        var preview = router.preview("public", Protocol.CHAT_COMPLETIONS, Set.of(ModelCapability.CHAT)).block();
        assertThat(preview.evaluation().getFirst().reason()).isEqualTo("INVALID_CAPABILITIES");
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 503); assertThat(calls).isEmpty();
        api.put().uri("/api/admin/provider-models/" + graph.model()).bodyValue(modelBody(graph.provider())).exchange().expectStatus().isOk();
        assertThat(get("/routing/validation")).isEmpty();
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 200);
    }
    @Test void rotatesStoredMaximumLengthCredentialsAndReportsOnlySafeFailureIds() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0);
        String maximum = "k".repeat(1024);
        api.put().uri("/api/admin/providers/" + graph.provider()).bodyValue(((ObjectNode) get("/providers/" + graph.provider())).put("apiKey", maximum))
                .exchange().expectStatus().isOk();
        assertThat(providers.findById(graph.provider()).block().apiKey()).isNull();
        var rotating = new CredentialService(providers, database, new com.llmgateway.config.CredentialProperties(true, false, "rotated", SyntheticKeyring.file()),
                new com.llmgateway.config.GatewayProperties("synthetic-gateway", "test"), new org.springframework.mock.env.MockEnvironment());
        var result = rotating.migrate(10, true).block();
        assertThat(result.migrated()).isEqualTo(1); assertThat(result.remainingLegacy()).isZero(); assertThat(result.remainingRotation()).isZero();
        assertThat(rotating.resolve(graph.provider()).block()).isEqualTo(maximum);
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 200);
        assertThat(calls.getFirst().authorization()).isEqualTo("Bearer " + maximum);
        database.sql("UPDATE providers SET api_key_ciphertext='v1.retired.synthetic.ciphertext' WHERE id=:id").bind("id", graph.provider()).then().block();
        var failed = rotating.migrate(10, true).block();
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.failures()).containsExactly(new CredentialService.MigrationFailure(graph.provider(), "CREDENTIAL_CONFIGURATION"));
        assertThat(mapper.valueToTree(failed).toString()).doesNotContain(maximum, "ciphertext", "retired");
        infer(Protocol.CHAT_COMPLETIONS, "public", false, 502); assertThat(calls).hasSize(1);
    }
    @Test void leaseHeartbeatsRenewAndOldOwnersCannotReleaseReplacementLeases() {
        var owned = leases.acquire("heartbeat", Duration.ofMillis(300)).block();
        var heartbeat = leases.heartbeat(owned).subscribe();
        try {
            StepVerifier.create(Mono.delay(Duration.ofMillis(700)).then(leases.owned(owned))).expectNext(true).verifyComplete();
            redis.delete(keys.discoveryLease("heartbeat")).block();
            var replacement = leases.acquire("heartbeat", Duration.ofSeconds(5)).block();
            leases.release(owned).block();
            assertThat(leases.owned(replacement).block()).isTrue();
            leases.release(replacement).block();
        } finally { heartbeat.dispose(); }
    }
    @Test void losingALeaseDuringDatabaseWritesRollsBackTheEntireCatalog() {
        Graph graph = graph("public", "a", Protocol.CHAT_COMPLETIONS, null, 0); enableDiscovery(graph.provider());
        long generation = reconciler.allocate(graph.provider()).block();
        var provider = providers.findById(graph.provider()).block();
        var lease = leases.acquire(graph.provider(), Duration.ofMillis(300)).block();
        var delayed = org.mockito.Mockito.mock(ProviderModelRepository.class, org.mockito.AdditionalAnswers.delegatesTo(providerModels));
        org.mockito.Mockito.doAnswer(call -> providerModels.save(call.<ProviderModelEntity>getArgument(0)).delayElement(Duration.ofMillis(400)))
                .when(delayed).save(org.mockito.ArgumentMatchers.any(ProviderModelEntity.class));
        var slower = new DiscoveryReconciler(providers, delayed, database, transactions, leases, discoveryProperties, Clock.systemUTC());
        StepVerifier.create(slower.apply(provider, lease, generation, List.of(), true)).expectError(GatewayException.class).verify();
        assertThat(providerModels.findById(graph.model()).block().missingCount()).isZero();
    }
    @Test void allRateLimitedCandidatesReturnABoundedRetryHintWithoutCountingServiceFailures() {
        String policy = policy(true, false, 0, 2, 800, 1);
        Graph first = graph("public", "a", Protocol.CHAT_COMPLETIONS, policy, 0);
        extra(first.virtual(), "b", Protocol.CHAT_COMPLETIONS, 1, 0);
        statuses.put("/a/v1/chat/completions", 429); statuses.put("/b/v1/chat/completions", 429);
        api.post().uri("/v1/chat/completions").bodyValue(request(Protocol.CHAT_COMPLETIONS, "public", false)).exchange()
                .expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "1");
        assertThat(calls).hasSize(2);
        assertThat(health.binding(first.binding()).block().failureCount()).isZero();
        assertThat(health.binding(first.binding()).block().rateLimitedCount()).isEqualTo(1);
        assertThat(circuit.snapshot(first.binding()).block().state()).isEqualTo(CircuitState.CLOSED);
    }
    private double totalTime(String name) {
        return meters.find(name).timers().stream().mapToDouble(timer -> timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).sum();
    }
    private String policy(boolean replay, boolean hedging, int count, int attempts, long delay, int threshold) {
        ObjectNode p = mapper.createObjectNode().put("name", "fixture-policy").put("strategy", "PRIORITY").put("hedgingEnabled", hedging).put("hedgeDelayMs", delay).put("maxHedgeCount", count);
        p.set("execution", mapper.valueToTree(new ExecutionPolicy(6000, attempts, replay, 1, 5, 0, threshold, 1000, 1)));
        return create("routing-policies", p).get("id").asText();
    }
    private Graph graph(String name, String path, Protocol protocol, String policy, int retries) {
        ObjectNode virtual = mapper.createObjectNode().put("name", name).put("enabled", true); if (policy != null) virtual.put("routingPolicyId", policy);
        String vm = create("virtual-models", virtual).get("id").asText();
        return extra(vm, path, protocol, 0, retries);
    }
    private Graph extra(String virtual, String path, Protocol protocol, int priority, int retries) {
        ObjectNode provider = mapper.createObjectNode().put("name", "fixture-" + path).put("baseUrl", "http://127.0.0.1:" + upstream.port() + "/" + path)
                .put("apiKey", "fixture-provider-key").put("protocol", "CHAT_COMPLETIONS").put("maxRetries", retries);
        String p = create("providers", provider).get("id").asText(); String m = create("provider-models", modelBody(p)).get("id").asText();
        String b = create("bindings", mapper.createObjectNode().put("virtualModelId", virtual).put("providerId", p).put("providerModelId", m)
                .put("priority", priority).put("sourceProtocol", protocol.name()).put("targetProtocol", "CHAT_COMPLETIONS").put("translationEnabled", protocol != Protocol.CHAT_COMPLETIONS)).get("id").asText();
        return new Graph(virtual, p, m, b);
    }
    private ObjectNode modelBody(String provider) { return mapper.createObjectNode().put("providerId", provider).put("modelName", "real-model").put("status", "ACTIVE").put("capabilities", "[\"CHAT\",\"STREAMING\",\"TOOLS\",\"VISION\",\"STRUCTURED_OUTPUT\"]"); }
    private void enableDiscovery(String id) { api.put().uri("/api/admin/providers/" + id).bodyValue(((ObjectNode) get("/providers/" + id)).put("modelDiscoveryEnabled", true)).exchange().expectStatus().isOk(); }
    private ObjectNode request(Protocol protocol, String name, boolean stream) {
        ObjectNode body = mapper.createObjectNode().put("model", name).put("stream", stream);
        if (protocol == Protocol.RESPONSES) body.put("input", "synthetic prompt");
        else body.set("messages", mapper.createArrayNode().add(mapper.createObjectNode().put("role", "user").put("content", "synthetic prompt")));
        if (protocol == Protocol.ANTHROPIC) body.put("max_tokens", 100);
        return body;
    }
    private String endpoint(Protocol protocol) { return switch (protocol) { case CHAT_COMPLETIONS -> "/v1/chat/completions"; case ANTHROPIC -> "/v1/messages"; case RESPONSES -> "/v1/responses"; }; }
    private JsonNode infer(Protocol protocol, String name, boolean stream, int status) {
        return api.post().uri(endpoint(protocol)).header("anthropic-version", "2023-06-01").bodyValue(request(protocol, name, stream))
                .exchange().expectStatus().isEqualTo(status).expectBody(JsonNode.class).returnResult().getResponseBody();
    }
    private String stream(Protocol protocol, String name) {
        return api.post().uri(endpoint(protocol)).header("anthropic-version", "2023-06-01").bodyValue(request(protocol, name, true))
                .exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).returnResult().getResponseBody();
    }
    private JsonNode create(String resource, JsonNode body) { return api.post().uri("/api/admin/" + resource).bodyValue(body).exchange().expectStatus().isEqualTo(resource.equals("model-rules") || resource.equals("routing-policies") ? 201 : 200).expectBody(JsonNode.class).returnResult().getResponseBody(); }
    private JsonNode get(String path) { return api.get().uri("/api/admin" + path).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody(); }
    private static String fixture(String name) throws java.io.IOException {
        try (var input = Objects.requireNonNull(GatewayRuntimeIntegrationTest.class.getResourceAsStream("/fixtures/" + name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String frame(String id, String delta, String finish) { return "data: {\"id\":\"" + id + "\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n"; }
    private record Graph(String virtual, String provider, String model, String binding) {}
    private record Captured(String path, String authorization, JsonNode body) {}
    private static final class MutableClock extends Clock {
        Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
