package com.llmgateway.infrastructure;

import com.llmgateway.admin.ProviderController;
import com.llmgateway.admin.ProviderOperationsService;
import com.llmgateway.admin.ProviderService;
import com.llmgateway.config.GatewayProperties;
import com.llmgateway.health.DashboardStore;
import com.llmgateway.protocol.RequestContext;
import java.net.URI;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

class GatewayWebFilterTest {
    private final ProviderService providers = mock(ProviderService.class);
    private final ProviderOperationsService operations = mock(ProviderOperationsService.class);

    private GatewayWebFilter filter() {
        return filter(new GatewayProperties("fixture-key", "fixture"));
    }

    private GatewayWebFilter filter(GatewayProperties properties) {
        DashboardStore dashboard = mock(DashboardStore.class);
        when(dashboard.request(anyString(), anyLong(), any())).thenReturn(Mono.empty());
        return new GatewayWebFilter(new AuthenticationService(properties),
                Clock.systemUTC(), mock(GatewayMetrics.class), dashboard);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/providers", "/api/%61dmin/providers", "/%61pi/admin/providers",
            "/api;ignored=x/admin/providers", "/api/admin;ignored=x/providers"
    })
    void protectsAdminReadsAndWritesForEveryPathAcceptedByTheRouter(String path) {
        WebTestClient client = WebTestClient.bindToController(new ProviderController(providers, operations))
                .webFilter(filter()).build();
        client.get().uri(URI.create(path)).exchange().expectStatus().isUnauthorized();
        client.post().uri(URI.create(path)).bodyValue("{}").exchange().expectStatus().isUnauthorized();
        client.delete().uri(URI.create(path + "/fixture")).exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(providers, operations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/%61dmin/providers", "/api;ignored=x/admin/providers"})
    void acceptsAuthenticatedAdminRequestsWithEncodedOrMatrixPaths(String path) {
        when(providers.list()).thenReturn(Flux.empty());
        WebTestClient client = WebTestClient.bindToController(new ProviderController(providers, operations))
                .webFilter(filter()).build();
        client.get().uri(URI.create(path)).headers(h -> h.setBearerAuth("fixture-key"))
                .exchange().expectStatus().isOk();
        verify(providers).list();
    }

    @Test
    void exposesOnlyHealthEndpointsWithoutAuthentication() {
        WebTestClient client = WebTestClient.bindToRouterFunction(route()
                .GET("/actuator/health", request -> ServerResponse.ok().build())
                .GET("/actuator/health/readiness", request -> ServerResponse.ok().build())
                .GET("/actuator/health-extra", request -> ServerResponse.ok().build())
                .GET("/actuator/prometheus", request -> ServerResponse.ok().build()).build())
                .webFilter(filter()).build();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health-extra").exchange().expectStatus().isUnauthorized();
        client.get().uri(URI.create("/%61ctuator/prometheus")).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void matchesEncodedInferencePathsToTheirProtocolAndKeepsAdminCredentialsSeparate() {
        WebTestClient client = WebTestClient.bindToRouterFunction(route()
                .GET("/api/admin/providers", request -> ServerResponse.ok().build())
                .GET("/actuator/prometheus", request -> ServerResponse.ok().build())
                .POST("/v1/messages", request -> {
                    RequestContext context = request.exchange().getAttribute(GatewayWebFilter.REQUEST_CONTEXT);
                    return ServerResponse.ok().bodyValue(context.protocol().name());
                }).build()).webFilter(filter(new GatewayProperties("client-key", "fixture", "admin-key"))).build();
        client.get().uri(URI.create("/api/%61dmin/providers")).headers(h -> h.setBearerAuth("client-key"))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/admin/providers").headers(h -> h.setBearerAuth("admin-key"))
                .exchange().expectStatus().isOk();
        client.get().uri("/actuator/prometheus").headers(h -> h.setBearerAuth("client-key"))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri(URI.create("/%761/%6dessages")).header("x-api-key", "client-key")
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("ANTHROPIC");
        client.post().uri(URI.create("/v1;ignored=x/messages")).headers(h -> h.setBearerAuth("admin-key"))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.type").isEqualTo("error");
    }
}
