package com.llmgateway.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.llmgateway.model.ModelCapabilities;
import com.llmgateway.model.Protocol;
import com.llmgateway.model.Provider;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
public class HttpProviderModelDiscovery implements ProviderModelDiscovery {
    private final WebClient.Builder webClient;

    public HttpProviderModelDiscovery(WebClient.Builder webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<List<DiscoveredModel>> discover(Provider provider) {
        return Mono.defer(() -> {
            HttpClient http = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(provider.connectTimeout().toMillis()))
                    .responseTimeout(provider.readTimeout());
            WebClient client = webClient.clone()
                    .clientConnector(new ReactorClientHttpConnector(http))
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                    .build();
            URI endpoint = endpoint(provider);
            var cursors = new HashSet<String>();
            return readPage(client, endpoint, provider)
                    .expand(page -> {
                        if (page.nextCursor() == null) return Mono.empty();
                        if (cursors.size() >= 100 || !cursors.add(page.nextCursor())) {
                            return Mono.error(invalidCatalog());
                        }
                        URI next = UriComponentsBuilder.fromUri(endpoint)
                                .replaceQueryParam("after_id", UriUtils.encodeQueryParam(page.nextCursor(), StandardCharsets.UTF_8))
                                .build(true).toUri();
                        return readPage(client, next, provider);
                    })
                    .concatMapIterable(CatalogPage::models)
                    .collect(LinkedHashMap<String, DiscoveredModel>::new, (models, model) -> models.put(model.modelName(), model))
                    .map(models -> List.copyOf(models.values()));
        }).timeout(provider.requestTimeout()).onErrorMap(error -> {
            if (error instanceof ProviderAccessException) return error;
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof TimeoutException || cause instanceof ReadTimeoutException || cause instanceof ConnectTimeoutException) {
                    return new ProviderAccessException(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT", "Provider request timed out");
                }
            }
            return new ProviderAccessException(HttpStatus.BAD_GATEWAY, "PROVIDER_UNAVAILABLE",
                    "Unable to read the provider model catalog; check its URL, credentials and response format");
        });
    }

    private Mono<CatalogPage> readPage(WebClient client, URI uri, Provider provider) {
        return client.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (provider.protocol() == Protocol.ANTHROPIC) {
                        headers.set("anthropic-version", "2023-06-01");
                        if (provider.apiKey() != null && !provider.apiKey().isBlank()) headers.set("x-api-key", provider.apiKey());
                    } else if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                        headers.setBearerAuth(provider.apiKey());
                    }
                })
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(invalidCatalog()));
                    }
                    int status = response.statusCode().value();
                    String code = status == 401 || status == 403 ? "PROVIDER_AUTHENTICATION_FAILED" : "PROVIDER_HTTP_ERROR";
                    return response.releaseBody().then(Mono.error(new ProviderAccessException(HttpStatus.BAD_GATEWAY,
                            code, "Provider model catalog returned HTTP " + status)));
                })
                .map(this::catalogPage);
    }

    private CatalogPage catalogPage(JsonNode root) {
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) throw invalidCatalog();
        List<DiscoveredModel> models = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode id = item.get("id");
            if (id == null || !id.isTextual() || id.asText().isBlank() || id.asText().length() > 255) {
                throw invalidCatalog();
            }
            String name = id.asText();
            JsonNode display = item.get("display_name");
            String displayName = display != null && display.isTextual() && display.asText().length() <= 255
                    ? display.asText() : name;
            models.add(new DiscoveredModel(name, displayName, ModelCapabilities.empty(), item));
        }
        JsonNode hasMore = root.get("has_more");
        if (hasMore != null && !hasMore.isBoolean()) throw invalidCatalog();
        String nextCursor = null;
        if (hasMore != null && hasMore.asBoolean()) {
            JsonNode lastId = root.get("last_id");
            if (models.isEmpty() || lastId == null || !lastId.isTextual() || lastId.asText().isBlank()) throw invalidCatalog();
            nextCursor = lastId.asText();
        }
        return new CatalogPage(List.copyOf(models), nextCursor);
    }

    private URI endpoint(Provider provider) {
        if (provider.modelDiscoveryUrl() != null && !provider.modelDiscoveryUrl().isBlank()) {
            return URI.create(provider.modelDiscoveryUrl());
        }
        URI base = URI.create(provider.baseUrl());
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        return UriComponentsBuilder.fromUri(base)
                .replacePath(path + (path.endsWith("/v1") ? "/models" : "/v1/models"))
                .build(true).toUri();
    }

    private static ProviderAccessException invalidCatalog() {
        return new ProviderAccessException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_CATALOG", "Provider returned an invalid model catalog");
    }

    private record CatalogPage(List<DiscoveredModel> models, String nextCursor) {}
}
