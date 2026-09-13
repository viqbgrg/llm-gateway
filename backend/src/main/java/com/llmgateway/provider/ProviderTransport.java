package com.llmgateway.provider;

import com.llmgateway.model.Provider;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;
import java.net.URI;

@Component
public class ProviderTransport {
    private final WebClient.Builder builder;
    public ProviderTransport(WebClient.Builder builder) { this.builder = builder; }
    public WebClient client(Provider provider, int maxBytes) {
        HttpClient http = HttpClient.create().followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(provider.connectTimeout().toMillis()))
                .responseTimeout(provider.readTimeout());
        return builder.clone().clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes)).build();
    }
    public static URI endpoint(String baseUrl, String resource) {
        URI base = URI.create(baseUrl);
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        return UriComponentsBuilder.fromUri(base).replacePath(path + (path.endsWith("/v1") ? "/" : "/v1/") + resource).build(true).toUri();
    }
    public static void authenticate(HttpHeaders headers, com.llmgateway.model.Protocol protocol, String credential) {
        if (protocol == com.llmgateway.model.Protocol.ANTHROPIC) {
            headers.set("anthropic-version", "2023-06-01");
            if (credential != null && !credential.isBlank()) headers.set("x-api-key", credential);
        } else if (credential != null && !credential.isBlank()) headers.setBearerAuth(credential);
    }
}
