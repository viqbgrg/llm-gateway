package com.llmgateway.routing;

import com.llmgateway.admin.*;
import com.llmgateway.config.*;
import com.llmgateway.inference.*;
import com.llmgateway.model.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfigurationStoreTest {
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final ProviderModelRepository models = mock(ProviderModelRepository.class);
    private final BindingRepository bindings = mock(BindingRepository.class);
    private final VirtualModelRepository virtuals = mock(VirtualModelRepository.class);

    private ConfigurationStore store() {
        var manager = mock(ReactiveTransactionManager.class);
        var transaction = mock(ReactiveTransaction.class);
        when(manager.getReactiveTransaction(any())).thenReturn(Mono.just(transaction));
        when(manager.commit(any())).thenReturn(Mono.empty());
        when(manager.rollback(any())).thenReturn(Mono.empty());
        when(virtuals.findById("virtual")).thenReturn(Mono.just(new VirtualModelEntity("virtual", "public", null, null,
                true, null, Instant.EPOCH, Instant.EPOCH, 7L)));
        return new ConfigurationStore(mock(VirtualModelResolver.class), virtuals, bindings, providers, models,
                mock(RoutingPolicyRepository.class), manager,
                new RoutingProperties(RoutingStrategy.PRIORITY, Duration.ofMillis(800), 1, false, 3, false,
                        Duration.ofMillis(100), Duration.ofSeconds(2), 0.2),
                new InferenceProperties(Duration.ofSeconds(60), 8388608, 8388608, 1048576, Duration.ofSeconds(30)),
                new ResilienceProperties(8, Duration.ofSeconds(30), 1));
    }

    private BindingEntity binding(String id, String model) {
        return new BindingEntity(id, "virtual", "provider", model, true, 0, false, Protocol.CHAT_COMPLETIONS,
                Protocol.CHAT_COMPLETIONS, null, Instant.EPOCH, Instant.EPOCH, 1L);
    }

    @Test
    void loadsLargeBindingSetsWithBoundedBatchesAndDeduplicatesSharedProviders() {
        var rows = new ArrayList<BindingEntity>();
        var modelRows = new HashMap<String, ProviderModelEntity>();
        for (int i = 0; i < 600; i++) {
            String id = "model-" + i;
            rows.add(binding("binding-" + i, id));
            modelRows.put(id, new ProviderModelEntity(id, "provider", "physical-" + i, null, ProviderModelStatus.ACTIVE,
                    "[\"CHAT\"]", null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 1L));
        }
        when(bindings.findByVirtualModelId("virtual")).thenReturn(Flux.fromIterable(rows));
        var provider = new ProviderEntity("provider", "fixture", "https://fixture.invalid", "synthetic-secret", true,
                Protocol.CHAT_COMPLETIONS, 5000, 30000, 60000, 0, false, null, 1800000, Instant.EPOCH, Instant.EPOCH, 1L);
        when(providers.findAllById(anyIterable())).thenReturn(Flux.just(provider));
        List<Integer> batchSizes = new ArrayList<>();
        when(models.findAllById(anyIterable())).thenAnswer(invocation -> {
            Iterable<String> ids = invocation.getArgument(0);
            List<String> batch = new ArrayList<>(); ids.forEach(batch::add); batchSizes.add(batch.size());
            return Flux.fromIterable(batch).map(modelRows::get);
        });
        var result = store().inspect("virtual").block();
        assertThat(result.candidates()).hasSize(600);
        assertThat(result.candidates()).extracting(c -> c.model().modelName()).contains("physical-0", "physical-599");
        assertThat(result.candidates()).extracting(c -> c.provider().apiKey()).containsOnlyNulls();
        assertThat(batchSizes).hasSizeLessThan(10).allMatch(size -> size > 0 && size <= 256);
        assertThat(batchSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(600);
        verify(providers).findAllById(List.of("provider"));
        verify(providers, never()).findById(anyString());
        verify(models, never()).findById(anyString());
    }

    @Test
    void missingReferencesFailTheWholeSnapshotInsteadOfDroppingCandidates() {
        when(bindings.findByVirtualModelId("virtual")).thenReturn(Flux.just(binding("binding", "missing")));
        when(providers.findAllById(anyIterable())).thenReturn(Flux.empty());
        when(models.findAllById(anyIterable())).thenReturn(Flux.empty());
        StepVerifier.create(store().inspect("virtual")).expectErrorSatisfies(error ->
                assertThat(((GatewayException) error).error()).isEqualTo(GatewayError.CONFIGURATION_CHANGED)).verify();
    }
}
