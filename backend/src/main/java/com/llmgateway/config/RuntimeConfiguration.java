package com.llmgateway.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class RuntimeConfiguration implements WebFluxConfigurer {
    private final InferenceProperties properties;
    public RuntimeConfiguration(InferenceProperties properties) { this.properties = properties; }
    @Bean public Clock clock() { return Clock.systemUTC(); }
    @Bean public org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .postConfigurer(mapper -> mapper.getFactory().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
    }
    @Override public void configureHttpMessageCodecs(ServerCodecConfigurer codecs) {
        codecs.defaultCodecs().maxInMemorySize(properties.maxRequestBytes());
    }
}
