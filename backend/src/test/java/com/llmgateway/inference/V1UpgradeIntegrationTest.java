package com.llmgateway.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.infrastructure.SyntheticKeyring;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
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
import reactor.netty.http.server.HttpServer;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.api-key=fixture-gateway-key", "gateway.discovery.enabled=false", "spring.flyway.enabled=true",
        "gateway.credentials.encrypted-writes=true", "gateway.credentials.allow-legacy-reads=true",
        "gateway.credentials.active-key-id=fixture"
})
@AutoConfigureWebTestClient
@Testcontainers
class V1UpgradeIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("upgrade_test").withUsername("upgrade_test").withPassword("synthetic-upgrade-database-key");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        // Seed the actual application's database at V1, before Boot applies V2 and later migrations.
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("1").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO providers(id,name,base_url,protocol,api_key,created_at,updated_at) VALUES('p','legacy-provider','http://fixture.invalid','CHAT_COMPLETIONS','synthetic-legacy',NOW(),NOW())");
            statement.execute("INSERT INTO provider_models(id,provider_id,model_name,status,capabilities,first_seen_at,last_seen_at,created_at,updated_at) VALUES('m','p','actual','ACTIVE','[\"CHAT\"]',NOW(),NOW(),NOW(),NOW())");
            statement.execute("INSERT INTO virtual_models(id,name,created_at,updated_at) VALUES('v','public',NOW(),NOW())");
            statement.execute("INSERT INTO virtual_model_bindings(id,virtual_model_id,provider_id,provider_model_id,source_protocol,target_protocol,created_at,updated_at) VALUES('b','v','p','m','CHAT_COMPLETIONS','CHAT_COMPLETIONS',NOW(),NOW())");
            statement.execute("INSERT INTO model_rules(id,pattern,created_at,updated_at) VALUES('r','alias-*',NOW(),NOW())");
        }
        properties.add("spring.r2dbc.url", () -> "r2dbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName());
        properties.add("spring.r2dbc.username", MYSQL::getUsername);
        properties.add("spring.r2dbc.password", MYSQL::getPassword);
        properties.add("spring.flyway.url", MYSQL::getJdbcUrl);
        properties.add("spring.flyway.user", MYSQL::getUsername);
        properties.add("spring.flyway.password", MYSQL::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("gateway.credentials.keyring-file", SyntheticKeyring::file);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    @Autowired Flyway flyway;

    @Test
    void upgradedV1GraphSupportsHttpCrudRuleRepairCredentialMigrationAndInference() {
        assertThat(flyway.info().applied()).extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5");
        WebTestClient api = client.mutate().defaultHeaders(headers -> headers.setBearerAuth("fixture-gateway-key"))
                .responseTimeout(Duration.ofSeconds(10)).build();
        ObjectNode provider = get(api, "providers/p");
        assertThat(provider.path("apiKey").asText()).isEqualTo("***");
        assertThat(provider.toString()).doesNotContain("synthetic-legacy");
        JsonNode model = get(api, "provider-models/m");
        assertThat(model.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(model.path("routingVersion").asLong()).isZero();
        JsonNode binding = get(api, "bindings/b");
        assertThat(binding.path("virtualModelId").asText()).isEqualTo("v");
        assertThat(binding.path("providerModelId").asText()).isEqualTo("m");
        ObjectNode rule = get(api, "model-rules/r");
        assertThat(rule.path("enabled").asBoolean()).isFalse();
        assertThat(rule.path("virtualModelId").isNull()).isTrue();

        List<String> authorizations = new CopyOnWriteArrayList<>();
        List<String> upstreamRequests = new CopyOnWriteArrayList<>();
        var upstream = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            authorizations.add(request.requestHeaders().get("Authorization"));
            return request.receive().aggregate().asString().flatMap(body -> {
                upstreamRequests.add(body);
                return response.header("Content-Type", "application/json").sendString(Mono.just("""
                        {"id":"upgrade-response","model":"actual","choices":[{"index":0,"message":{"role":"assistant","content":"upgraded"},"finish_reason":"stop"}]}
                        """)).then();
            });
        }).bindNow();
        try {
            // Updating an old provider through HTTP must preserve its omitted/masked credential.
            provider.put("baseUrl", "http://127.0.0.1:" + upstream.port());
            put(api, "providers/p", provider);
            infer(api, "public");
            assertThat(upstreamRequests).hasSize(1);

            ObjectNode policy = api.post().uri("/api/admin/routing-policies")
                    .bodyValue(mapper.createObjectNode().put("name", "after-upgrade").put("strategy", "PRIORITY").put("hedgeDelayMs", 800))
                    .exchange().expectStatus().isCreated().expectBody(ObjectNode.class).returnResult().getResponseBody();
            String policyId = policy.path("id").asText();
            ObjectNode virtual = get(api, "virtual-models/v").put("displayName", "after upgrade").put("routingPolicyId", policyId);
            put(api, "virtual-models/v", virtual);
            assertThat(get(api, "virtual-models/v").path("displayName").asText()).isEqualTo("after upgrade");
            api.delete().uri("/api/admin/routing-policies/" + policyId).exchange().expectStatus().isEqualTo(409);
            put(api, "model-rules/r", rule.put("enabled", true).put("virtualModelId", "v"));
            infer(api, "alias-upgraded");

            api.post().uri("/api/admin/credentials/migrate").bodyValue(mapper.createObjectNode().put("batchSize", 100).put("rotate", false))
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.migrated").isEqualTo(1)
                    .jsonPath("$.remainingLegacy").isEqualTo(0).jsonPath("$.failed").isEqualTo(0);
            assertThat(database.sql("SELECT COUNT(*) AS total FROM providers WHERE api_key IS NOT NULL")
                    .map((row, metadata) -> row.get("total", Long.class)).one().block()).isZero();
            assertThat(database.sql("SELECT api_key_ciphertext FROM providers WHERE id='p'")
                    .map((row, metadata) -> row.get("api_key_ciphertext", String.class)).one().block())
                    .startsWith("v1.fixture.").doesNotContain("synthetic-legacy");
            infer(api, "alias-encrypted");
            assertThat(upstreamRequests).hasSize(3);
            assertThat(authorizations).containsOnly("Bearer synthetic-legacy");
            upstreamRequests.forEach(body -> {
                assertThat(com.llmgateway.protocol.ProtocolJson.read(body).path("model").asText()).isEqualTo("actual");
                assertThat(body).doesNotContain("synthetic-legacy");
            });
            assertThat(get(api, "provider-models/m").path("firstSeenAt")).isEqualTo(model.path("firstSeenAt"));
            assertThat(get(api, "providers/p").toString()).doesNotContain("synthetic-legacy", "v1.fixture.");

            // Delete the upgraded graph in dependency order, exercising its new and retained constraints.
            for (String resource : List.of("bindings/b", "model-rules/r", "virtual-models/v", "provider-models/m", "routing-policies/" + policyId, "providers/p")) {
                api.delete().uri("/api/admin/" + resource).exchange().expectStatus().isNoContent();
                api.get().uri("/api/admin/" + resource).exchange().expectStatus().isNotFound();
            }
        } finally {
            upstream.disposeNow();
        }
    }

    private ObjectNode get(WebTestClient api, String resource) {
        return api.get().uri("/api/admin/" + resource).exchange().expectStatus().isOk()
                .expectBody(ObjectNode.class).returnResult().getResponseBody();
    }

    private void put(WebTestClient api, String resource, ObjectNode body) {
        api.put().uri("/api/admin/" + resource).bodyValue(body).exchange().expectStatus().isOk();
    }

    private void infer(WebTestClient api, String model) {
        ObjectNode body = mapper.createObjectNode().put("model", model);
        body.set("messages", mapper.createArrayNode().add(mapper.createObjectNode().put("role", "user").put("content", "Synthetic upgrade verification")));
        api.post().uri("/v1/chat/completions").bodyValue(body).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.model").isEqualTo(model).jsonPath("$.choices[0].message.content").isEqualTo("upgraded");
    }
}
