package com.llmgateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.api-key=fixture-gateway-key",
        "spring.flyway.enabled=true",
        "management.endpoint.health.show-details=always"
})
@AutoConfigureWebTestClient
@Testcontainers
class AdminApiIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("llm_gateway").withUsername("gateway_test").withPassword("fixture-database-password");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.r2dbc.url", () -> "r2dbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName());
        properties.add("spring.r2dbc.username", MYSQL::getUsername);
        properties.add("spring.r2dbc.password", MYSQL::getPassword);
        properties.add("spring.flyway.url", MYSQL::getJdbcUrl);
        properties.add("spring.flyway.user", MYSQL::getUsername);
        properties.add("spring.flyway.password", MYSQL::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    @Autowired ProviderRepository providers;
    private WebTestClient api;
    private DisposableServer upstream;
    private final AtomicInteger upstreamStatus = new AtomicInteger(200);
    private final AtomicReference<String> upstreamBody = new AtomicReference<>("{\"data\":[]}");

    @BeforeEach
    void resetConfiguration() {
        api = client.mutate().defaultHeaders(headers -> headers.setBearerAuth("fixture-gateway-key"))
                .responseTimeout(Duration.ofSeconds(10)).build();
        database.sql("DELETE FROM virtual_model_bindings").then()
                .then(database.sql("DELETE FROM provider_models").then())
                .then(database.sql("DELETE FROM virtual_models").then())
                .then(database.sql("DELETE FROM providers").then())
                .block(Duration.ofSeconds(10));
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) upstream.disposeNow();
    }

    @Test
    void migratesMysqlAndConnectsToBothConfigurationAndRuntimeStorage() {
        Long migrations = database.sql("SELECT COUNT(*) AS total FROM flyway_schema_history WHERE success = 1")
                .map((row, metadata) -> row.get("total", Long.class)).one().block(Duration.ofSeconds(5));
        assertThat(migrations).isEqualTo(1);
        client.get().uri("/actuator/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.r2dbc.status").isEqualTo("UP")
                .jsonPath("$.components.redis.status").isEqualTo("UP");
    }

    @Test
    void protectsEveryAdminResourceAndProviderAction() {
        for (String resource : List.of("providers", "provider-models", "virtual-models", "bindings")) {
            client.get().uri("/api/admin/" + resource).exchange().expectStatus().isUnauthorized()
                    .expectHeader().exists("X-Request-ID");
            client.post().uri("/api/admin/" + resource).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isUnauthorized();
        }
        for (String action : List.of("test-connection", "sync-models")) {
            client.post().uri("/api/admin/providers/missing/" + action).exchange().expectStatus().isUnauthorized();
        }
        api.get().uri("/api/admin/providers").header("X-Request-ID", "fixture-request")
                .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Request-ID", "fixture-request");
    }

    @Test
    void createsReadsEditsTogglesAndDeletesTheEntireConfigurationGraph() {
        JsonNode provider = create("providers", providerRequest("original", "http://localhost:9000"));
        String providerId = id(provider);
        assertThat(provider.get("apiKey").asText()).isEqualTo("***");
        JsonNode model = create("provider-models", modelRequest(providerId, "actual-model"));
        JsonNode virtualModel = create("virtual-models", virtualModelRequest("public-model"));
        JsonNode binding = create("bindings", bindingRequest(id(virtualModel), providerId, id(model)));

        for (var resource : List.of("providers", "provider-models", "virtual-models", "bindings")) {
            assertThat(get("/" + resource)).hasSize(1);
        }
        assertThat(get("/providers/" + providerId).get("name").asText()).isEqualTo("original");
        assertThat(get("/provider-models/" + id(model)).get("status").asText()).isEqualTo("NEW");
        assertThat(get("/virtual-models/" + id(virtualModel)).get("name").asText()).isEqualTo("public-model");
        assertThat(get("/bindings/" + id(binding)).get("providerModelId").asText()).isEqualTo(id(model));
        assertThat(get("/provider-models?providerId=" + providerId)).hasSize(1);
        assertThat(get("/provider-models?providerId=missing")).isEmpty();
        assertThat(get("/bindings?virtualModelId=" + id(virtualModel))).hasSize(1);
        assertThat(get("/bindings?virtualModelId=missing")).isEmpty();

        ObjectNode providerEdit = providerRequest("renamed", "http://localhost:9100").put("enabled", false);
        providerEdit.remove("apiKey");
        JsonNode editedProvider = update("providers", providerId, providerEdit);
        assertThat(editedProvider.get("name").asText()).isEqualTo("renamed");
        assertThat(editedProvider.get("enabled").asBoolean()).isFalse();
        assertThat(storedKey(providerId)).isEqualTo("fixture-provider-key");
        assertThat(update("providers", providerId, providerEdit.put("enabled", true)).get("enabled").asBoolean()).isTrue();

        ObjectNode modelEdit = modelRequest(providerId, "actual-model").put("displayName", "Edited model")
                .put("status", "ACTIVE").put("capabilities", "[\"CHAT\",\"TOOLS\"]");
        assertThat(update("provider-models", id(model), modelEdit).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(update("provider-models", id(model), modelEdit.put("status", "DISABLED")).get("status").asText()).isEqualTo("DISABLED");
        ObjectNode virtualEdit = virtualModelRequest("renamed-public-model").put("enabled", false).put("description", "Edited description");
        assertThat(update("virtual-models", id(virtualModel), virtualEdit).get("enabled").asBoolean()).isFalse();
        assertThat(update("virtual-models", id(virtualModel), virtualEdit.put("enabled", true)).get("enabled").asBoolean()).isTrue();
        ObjectNode bindingEdit = bindingRequest(id(virtualModel), providerId, id(model))
                .put("priority", 42).put("enabled", false).put("capabilitiesOverride", "[\"CHAT\"]");
        JsonNode editedBinding = update("bindings", id(binding), bindingEdit);
        assertThat(editedBinding.get("priority").asInt()).isEqualTo(42);
        assertThat(editedBinding.get("enabled").asBoolean()).isFalse();
        assertThat(update("bindings", id(binding), bindingEdit.put("enabled", true).putNull("capabilitiesOverride"))
                .get("capabilitiesOverride").isNull()).isTrue();

        api.delete().uri("/api/admin/providers/" + providerId).exchange().expectStatus().isEqualTo(409);
        api.delete().uri("/api/admin/provider-models/" + id(model)).exchange().expectStatus().isEqualTo(409);
        api.delete().uri("/api/admin/virtual-models/" + id(virtualModel)).exchange().expectStatus().isEqualTo(409);
        delete("bindings", id(binding));
        delete("provider-models", id(model));
        delete("virtual-models", id(virtualModel));
        delete("providers", providerId);
        assertThat(get("/providers")).isEmpty();
    }

    @Test
    void preservesMaskedAndOmittedKeysButAllowsReplacementAndExplicitRemoval() {
        JsonNode created = create("providers", providerRequest("credentials", "http://localhost:9000"));
        String id = id(created);
        ObjectNode edit = providerRequest("credentials", "http://localhost:9000").put("apiKey", "***");
        assertThat(update("providers", id, edit).get("apiKey").asText()).isEqualTo("***");
        assertThat(storedKey(id)).isEqualTo("fixture-provider-key");
        edit.remove("apiKey");
        update("providers", id, edit);
        assertThat(storedKey(id)).isEqualTo("fixture-provider-key");
        assertThat(update("providers", id, edit.put("apiKey", "replacement-fixture-key")).get("apiKey").asText()).isEqualTo("***");
        assertThat(storedKey(id)).isEqualTo("replacement-fixture-key");
        assertThat(update("providers", id, edit.put("apiKey", "")).get("apiKey").isNull()).isTrue();
        assertThat(storedKey(id)).isNull();
    }

    @Test
    void rejectsInvalidInputsAndDoesNotCreateRecordsForMissingUpdateTargets() {
        for (String resource : List.of("providers", "provider-models", "virtual-models", "bindings")) {
            api.post().uri("/api/admin/" + resource).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isBadRequest();
            api.get().uri("/api/admin/" + resource + "/missing").exchange().expectStatus().isNotFound();
            api.delete().uri("/api/admin/" + resource + "/missing").exchange().expectStatus().isNotFound();
        }
        expectMissingUpdate("providers", providerRequest("missing", "http://localhost:9000"));
        expectMissingUpdate("provider-models", modelRequest("missing-provider", "missing"));
        expectMissingUpdate("virtual-models", virtualModelRequest("missing"));
        expectMissingUpdate("bindings", bindingRequest("missing", "missing", "missing"));
        assertThat(get("/providers")).isEmpty();
        assertThat(get("/virtual-models")).isEmpty();

        api.post().uri("/api/admin/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerRequest("invalid", "http://user:fixture-provider-key@example.com"))
                .exchange().expectStatus().isBadRequest().expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("fixture-provider-key"));
        api.post().uri("/api/admin/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerRequest("invalid", "http://localhost").put("requestTimeoutMs", 0))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void rejectsCrossProviderBindingsAndMovingAnExistingProviderModel() {
        String first = id(create("providers", providerRequest("first", "http://localhost:9000")));
        String second = id(create("providers", providerRequest("second", "http://localhost:9001")));
        String model = id(create("provider-models", modelRequest(first, "actual-model")));
        String virtualModel = id(create("virtual-models", virtualModelRequest("public-model")));
        api.post().uri("/api/admin/bindings").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bindingRequest(virtualModel, second, model)).exchange().expectStatus().isBadRequest();
        api.put().uri("/api/admin/provider-models/" + model).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(modelRequest(second, "actual-model")).exchange().expectStatus().isBadRequest();
        assertThat(get("/bindings")).isEmpty();
        assertThat(get("/provider-models/" + model).get("providerId").asText()).isEqualTo(first);
    }

    @Test
    void rejectsDuplicatesAndMalformedJsonWithoutLeakingStorageErrors() {
        String providerId = id(create("providers", providerRequest("first", "http://localhost:9000")));
        create("provider-models", modelRequest(providerId, "duplicate"));
        api.post().uri("/api/admin/provider-models").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(modelRequest(providerId, "duplicate")).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("CONFIGURATION_CONFLICT");
        for (String capabilities : List.of("{", "{}", "null", "[] trailing")) {
            api.post().uri("/api/admin/provider-models").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(modelRequest(providerId, "invalid").put("capabilities", capabilities))
                    .exchange().expectStatus().isBadRequest();
        }
        create("virtual-models", virtualModelRequest("duplicate"));
        api.post().uri("/api/admin/virtual-models").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(virtualModelRequest("duplicate")).exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void testsConnectionsWithoutImportingModelsAndChecksMissingProviders() {
        upstreamBody.set("{\"data\":[{\"id\":\"first\"},{\"id\":\"second\"}]}");
        String providerId = fixtureProvider();
        JsonNode result = action(providerId, "test-connection");
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("modelCount").asInt()).isEqualTo(2);
        assertThat(result.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(get("/provider-models")).isEmpty();
        for (String action : List.of("test-connection", "sync-models")) {
            api.post().uri("/api/admin/providers/missing/" + action).exchange().expectStatus().isNotFound();
        }
    }

    @Test
    void importsIdempotentlyWhilePreservingManualStatusCapabilitiesAndMissingModels() {
        upstreamBody.set("{\"data\":[{\"id\":\"first\"},{\"id\":\"second\"}]}");
        String providerId = fixtureProvider();
        JsonNode firstSync = action(providerId, "sync-models");
        assertThat(firstSync.get("created").asInt()).isEqualTo(2);
        assertThat(firstSync.get("updated").asInt()).isZero();
        JsonNode first = modelByName(providerId, "first");
        JsonNode second = modelByName(providerId, "second");
        assertThat(first.get("status").asText()).isEqualTo("NEW");
        String firstSeen = first.get("firstSeenAt").asText();
        update("provider-models", id(first), modelRequest(providerId, "first")
                .put("status", "DISABLED").put("displayName", "Custom display").put("capabilities", "[\"TOOLS\"]"));
        create("provider-models", modelRequest(providerId, "manual").put("status", "ACTIVE"));

        upstreamBody.set("{\"data\":[{\"id\":\"first\",\"owned_by\":\"updated\"},{\"id\":\"third\"}]}");
        JsonNode secondSync = action(providerId, "sync-models");
        assertThat(secondSync.get("created").asInt()).isEqualTo(1);
        assertThat(secondSync.get("updated").asInt()).isEqualTo(1);
        JsonNode updated = modelByName(providerId, "first");
        assertThat(id(updated)).isEqualTo(id(first));
        assertThat(updated.get("firstSeenAt").asText()).isEqualTo(firstSeen);
        assertThat(updated.get("status").asText()).isEqualTo("DISABLED");
        assertThat(updated.get("displayName").asText()).isEqualTo("Custom display");
        assertThat(updated.get("capabilities").asText()).isEqualTo("[\"TOOLS\"]");
        assertThat(updated.get("rawMetadata").asText()).contains("updated");
        assertThat(modelByName(providerId, "second")).isEqualTo(second);
        assertThat(modelByName(providerId, "manual").get("status").asText()).isEqualTo("ACTIVE");

        assertThat(action(providerId, "sync-models").get("created").asInt()).isZero();
        assertThat(get("/provider-models?providerId=" + providerId)).hasSize(4);
        assertThat(get("/virtual-models")).isEmpty();
        assertThat(get("/bindings")).isEmpty();
    }

    @Test
    void leavesConfigurationUntouchedAfterFailedMalformedOrEmptyCatalogs() {
        upstreamBody.set("{\"data\":[{\"id\":\"existing\"}]}");
        String providerId = fixtureProvider();
        action(providerId, "sync-models");
        JsonNode before = get("/provider-models");
        upstreamStatus.set(401);
        upstreamBody.set("{\"error\":\"fixture-provider-key\"}");
        api.post().uri("/api/admin/providers/" + providerId + "/sync-models").exchange().expectStatus().isEqualTo(502)
                .expectBody(String.class).value(body -> assertThat(body).doesNotContain("fixture-provider-key"));
        assertThat(get("/provider-models")).isEqualTo(before);

        upstreamStatus.set(200);
        upstreamBody.set("{\"data\":[{\"id\":\"would-be-new\"},{}]}");
        api.post().uri("/api/admin/providers/" + providerId + "/sync-models").exchange().expectStatus().isEqualTo(502);
        assertThat(get("/provider-models")).isEqualTo(before);
        upstreamBody.set("{\"data\":[]}");
        assertThat(action(providerId, "sync-models").get("total").asInt()).isZero();
        assertThat(get("/provider-models")).isEqualTo(before);
    }

    @Test
    void serializesConcurrentImportsForTheSameProvider() throws Exception {
        upstreamBody.set("{\"data\":[{\"id\":\"first\"},{\"id\":\"second\"}]}");
        String providerId = fixtureProvider();
        var first = CompletableFuture.supplyAsync(() -> action(providerId, "sync-models"));
        var second = CompletableFuture.supplyAsync(() -> action(providerId, "sync-models"));
        JsonNode firstResult = first.get(20, TimeUnit.SECONDS);
        JsonNode secondResult = second.get(20, TimeUnit.SECONDS);
        assertThat(firstResult.get("created").asInt() + secondResult.get("created").asInt()).isEqualTo(2);
        assertThat(firstResult.get("updated").asInt() + secondResult.get("updated").asInt()).isEqualTo(2);
        assertThat(get("/provider-models?providerId=" + providerId)).hasSize(2);
    }

    private String fixtureProvider() {
        upstream = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) ->
                response.status(upstreamStatus.get()).header("Content-Type", "application/json")
                        .sendString(Mono.just(upstreamBody.get()))).bindNow();
        return id(create("providers", providerRequest("fixture", "http://127.0.0.1:" + upstream.port())
                .put("modelDiscoveryEnabled", false)));
    }

    private ObjectNode providerRequest(String name, String url) {
        return mapper.createObjectNode().put("name", name).put("baseUrl", url).put("apiKey", "fixture-provider-key")
                .put("protocol", "CHAT_COMPLETIONS");
    }

    private ObjectNode modelRequest(String providerId, String name) {
        return mapper.createObjectNode().put("providerId", providerId).put("modelName", name).put("capabilities", "[]");
    }

    private ObjectNode virtualModelRequest(String name) {
        return mapper.createObjectNode().put("name", name).put("displayName", "Public model");
    }

    private ObjectNode bindingRequest(String virtualModelId, String providerId, String modelId) {
        return mapper.createObjectNode().put("virtualModelId", virtualModelId).put("providerId", providerId).put("providerModelId", modelId);
    }

    private JsonNode create(String resource, JsonNode request) {
        return api.post().uri("/api/admin/" + resource).contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private JsonNode update(String resource, String id, JsonNode request) {
        return api.put().uri("/api/admin/" + resource + "/" + id).contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private void expectMissingUpdate(String resource, JsonNode request) {
        api.put().uri("/api/admin/" + resource + "/missing").contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isNotFound();
    }

    private JsonNode get(String path) {
        return api.get().uri("/api/admin" + path).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private void delete(String resource, String id) {
        api.delete().uri("/api/admin/" + resource + "/" + id).exchange().expectStatus().isNoContent();
        api.get().uri("/api/admin/" + resource + "/" + id).exchange().expectStatus().isNotFound();
    }

    private JsonNode action(String providerId, String action) {
        return api.post().uri("/api/admin/providers/" + providerId + "/" + action).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private JsonNode modelByName(String providerId, String modelName) {
        return StreamSupport.stream(get("/provider-models?providerId=" + providerId).spliterator(), false)
                .filter(model -> model.get("modelName").asText().equals(modelName)).findFirst().orElseThrow();
    }

    private String storedKey(String id) {
        return providers.findById(id).block(Duration.ofSeconds(5)).apiKey();
    }

    private String id(JsonNode entity) {
        return entity.get("id").asText();
    }
}
