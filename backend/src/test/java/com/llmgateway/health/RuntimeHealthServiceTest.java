package com.llmgateway.health;

import com.llmgateway.admin.*;
import com.llmgateway.model.*;
import com.llmgateway.protocol.TranslationValidator;
import com.llmgateway.resilience.*;
import com.llmgateway.routing.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeHealthServiceTest {
    private final BindingRepository bindings = mock(BindingRepository.class);
    private final ConfigurationStore configurations = mock(ConfigurationStore.class);
    private final DashboardStore dashboard = mock(DashboardStore.class);

    private RuntimeHealthService service() {
        var providers = mock(ProviderRepository.class);
        var models = mock(ProviderModelRepository.class);
        var health = mock(HealthManager.class);
        var circuits = mock(CircuitBreaker.class);
        var preferences = mock(PreferredBindingStore.class);
        when(providers.findAll()).thenReturn(Flux.empty());
        when(models.findAll()).thenReturn(Flux.empty());
        when(health.binding(anyString())).thenReturn(Mono.just(HealthSnapshot.unknown()));
        when(circuits.snapshot(anyString())).thenReturn(Mono.just(CircuitSnapshot.unknown()));
        when(preferences.read(anyString())).thenReturn(Mono.empty());
        return new RuntimeHealthService(bindings, providers, models, configurations, new TranslationValidator(),
                health, circuits, preferences, dashboard, Clock.systemUTC());
    }

    @Test
    void monitoringReadsOneSnapshotPerVirtualModelEvenWithManyBindings() {
        var rows = new ArrayList<BindingEntity>();
        var candidates = new ArrayList<RoutingCandidate>();
        var provider = new Provider("provider", "fixture", "https://fixture.invalid", null, true, Protocol.CHAT_COMPLETIONS,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60), 0, false, null,
                Duration.ofMinutes(30), Instant.EPOCH, Instant.EPOCH);
        var model = new ProviderModel("model", "provider", "physical", null, ProviderModelStatus.ACTIVE,
                new ModelCapabilities(Set.of(ModelCapability.CHAT)), null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
        for (int i = 0; i < 100; i++) {
            String id = "binding-" + i;
            rows.add(new BindingEntity(id, "virtual", "provider", "model", true, i, false, Protocol.CHAT_COMPLETIONS,
                    Protocol.CHAT_COMPLETIONS, null, Instant.EPOCH, Instant.EPOCH, 1L));
            candidates.add(new RoutingCandidate(new VirtualModelBinding(id, "virtual", "provider", "model", true, i, false,
                    Protocol.CHAT_COMPLETIONS, Protocol.CHAT_COMPLETIONS, null, Instant.EPOCH, Instant.EPOCH), provider, model, 1, 1, 1));
        }
        when(bindings.findAll()).thenReturn(Flux.fromIterable(rows));
        when(configurations.inspect("virtual")).thenReturn(Mono.just(new ConfigurationSnapshot(
                new VirtualModel("virtual", "public", null, null, true, null, Instant.EPOCH, Instant.EPOCH),
                1, RoutingPolicy.defaults(), candidates)));
        var result = service().all().block();
        assertThat(result.bindings()).hasSize(100).allMatch(RuntimeHealthService.BindingView::available);
        verify(configurations).inspect("virtual");
    }

    @Test
    void deletingAModelDuringMonitoringDoesNotBreakTheWholePage() {
        when(bindings.findAll()).thenReturn(Flux.just(new BindingEntity("old", "deleted", "provider", "model", true, 0,
                false, Protocol.CHAT_COMPLETIONS, Protocol.CHAT_COMPLETIONS, null, Instant.EPOCH, Instant.EPOCH, 1L)));
        when(configurations.inspect("deleted")).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        assertThat(service().all().block().bindings()).isEmpty();
    }
}
