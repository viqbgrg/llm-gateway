package com.llmgateway.discovery;

import com.llmgateway.admin.ProviderEntity;
import com.llmgateway.admin.ProviderRepository;
import com.llmgateway.config.DiscoveryProperties;
import com.llmgateway.model.Protocol;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscoverySchedulerTest {
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final DiscoveryCoordinator coordinator = mock(DiscoveryCoordinator.class);
    private final DiscoveryStatusStore statuses = mock(DiscoveryStatusStore.class);
    private final VirtualTimeScheduler timer = VirtualTimeScheduler.create();
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(timer.now(java.util.concurrent.TimeUnit.MILLISECONDS)); }
    };

    @Test void theGlobalSwitchPreventsStorageAndProviderAccess() {
        StepVerifier.create(scheduler(false, 2, Duration.ZERO).scan()).verifyComplete();
        verifyNoInteractions(providers, coordinator, statuses);
    }

    @Test void disabledProvidersAreSkippedAndChangedIntervalsTakeEffectOnTheNextScan() {
        when(providers.findAll()).thenReturn(Flux.just(provider("disabled", false, true), provider("manual", true, false), provider("due", true, true)));
        when(statuses.read("due")).thenReturn(Mono.just(status("due", "SUCCESS", 0L, 0L, 10_000)));
        when(statuses.initialize(anyString(), anyLong())).thenReturn(Mono.empty());
        when(coordinator.interval(any())).thenReturn(10_000L);
        var scheduler = scheduler(true, 2, Duration.ZERO);
        StepVerifier.create(scheduler.scan()).verifyComplete();
        verify(coordinator, never()).automatic(anyString());
        timer.advanceTimeBy(Duration.ofSeconds(2));
        when(coordinator.interval(any())).thenReturn(1_000L);
        when(coordinator.automatic("due")).thenReturn(Mono.just(result()));
        StepVerifier.create(scheduler.scan()).verifyComplete();
        verify(coordinator).automatic("due");
        verify(statuses, never()).read("disabled");
        verify(statuses, never()).read("manual");
    }

    @Test void failureBackoffDelaysOnlyTheFailedProvider() {
        when(providers.findAll()).thenReturn(Flux.just(provider("failed", true, true), provider("ready", true, true)));
        when(statuses.read("failed")).thenReturn(Mono.just(status("failed", "FAILED", 0L, null, 2000)));
        when(statuses.read("ready")).thenReturn(Mono.just(status("ready", "WAITING", null, null, 0)));
        when(statuses.initialize(anyString(), anyLong())).thenReturn(Mono.empty());
        when(coordinator.interval(any())).thenReturn(10_000L);
        when(coordinator.automatic("ready")).thenReturn(Mono.just(result()));
        StepVerifier.create(scheduler(true, 2, Duration.ZERO).scan()).verifyComplete();
        verify(coordinator, never()).automatic("failed");
        verify(coordinator).automatic("ready");
        timer.advanceTimeBy(Duration.ofSeconds(2));
        when(coordinator.automatic("failed")).thenReturn(Mono.error(new IllegalStateException("synthetic failure")));
        StepVerifier.create(scheduler(true, 2, Duration.ZERO).scan()).verifyComplete();
        verify(coordinator).automatic("failed");
        verify(coordinator, times(2)).automatic("ready");
    }

    @Test void startupJitterUsesTheInjectedClockAndTimer() {
        when(providers.findAll()).thenReturn(Flux.just(provider("p", true, true)));
        when(statuses.read("p")).thenReturn(Mono.empty());
        when(statuses.initialize(eq("p"), anyLong())).thenAnswer(call -> Mono.just(status("p", "WAITING", null, null, call.getArgument(1))));
        when(coordinator.interval(any())).thenReturn(10_000L);
        when(coordinator.automatic("p")).thenReturn(Mono.just(result()));
        StepVerifier.withVirtualTime(() -> scheduler(true, 2, Duration.ofSeconds(5)).scan(), () -> timer, Long.MAX_VALUE)
                .then(() -> verify(coordinator, never()).automatic(anyString()))
                .thenAwait(Duration.ofMillis(112)).verifyComplete();
        verify(coordinator).automatic("p");
    }

    @Test void concurrencyIsBoundedAndShutdownCancelsRunningTasksAndFutureScans() {
        when(providers.findAll()).thenReturn(Flux.just(provider("a", true, true), provider("b", true, true), provider("c", true, true)));
        when(statuses.read(anyString())).thenAnswer(call -> Mono.just(status(call.getArgument(0), "WAITING", null, null, 0)));
        when(statuses.initialize(anyString(), anyLong())).thenReturn(Mono.empty());
        when(coordinator.interval(any())).thenReturn(10_000L);
        AtomicInteger active = new AtomicInteger(); AtomicInteger cancelled = new AtomicInteger();
        when(coordinator.automatic(anyString())).thenAnswer(call -> Mono.<DiscoveryReconciler.Result>never()
                .doOnSubscribe(s -> active.incrementAndGet()).doOnCancel(cancelled::incrementAndGet));
        var scheduler = scheduler(true, 2, Duration.ZERO);
        scheduler.start(); timer.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(active.get()).isEqualTo(2);
        scheduler.stop(); timer.advanceTimeBy(Duration.ofMinutes(1));
        assertThat(cancelled.get()).isEqualTo(2); assertThat(active.get()).isEqualTo(2);
    }

    private DiscoveryScheduler scheduler(boolean enabled, int concurrency, Duration jitter) {
        return new DiscoveryScheduler(providers, coordinator, statuses, new DiscoveryProperties(enabled, Duration.ofMinutes(30),
                Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofSeconds(5), 2, concurrency, jitter), clock, timer);
    }
    private ProviderEntity provider(String id, boolean enabled, boolean automatic) {
        return new ProviderEntity(id, id, "http://fixture.invalid", null, enabled, Protocol.CHAT_COMPLETIONS, 5000, 30000, 60000,
                0, automatic, null, 10000, Instant.EPOCH, Instant.EPOCH, 0L);
    }
    private DiscoveryStatusStore.Status status(String id, String state, Long attempt, Long success, long next) {
        return new DiscoveryStatusStore.Status(id, state, attempt, success, next, 0, 0, 0, 0, 0, 0, 0, null, 0, 0);
    }
    private DiscoveryReconciler.Result result() { return new DiscoveryReconciler.Result(0, 0, 0, 0, 0, 0, 1); }
}
