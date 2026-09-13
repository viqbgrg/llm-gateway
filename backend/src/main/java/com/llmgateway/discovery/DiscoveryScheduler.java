package com.llmgateway.discovery;

import com.llmgateway.admin.ProviderRepository;
import com.llmgateway.config.DiscoveryProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DiscoveryScheduler {
    private final ProviderRepository providers;
    private final DiscoveryCoordinator coordinator;
    private final DiscoveryStatusStore statuses;
    private final DiscoveryProperties properties;
    private final Clock clock;
    private final reactor.core.scheduler.Scheduler scheduler;
    private Disposable subscription;
    @org.springframework.beans.factory.annotation.Autowired
    public DiscoveryScheduler(ProviderRepository providers, DiscoveryCoordinator coordinator, DiscoveryStatusStore statuses, DiscoveryProperties properties, Clock clock) {
        this(providers, coordinator, statuses, properties, clock, reactor.core.scheduler.Schedulers.parallel());
    }
    public DiscoveryScheduler(ProviderRepository providers, DiscoveryCoordinator coordinator, DiscoveryStatusStore statuses,
                              DiscoveryProperties properties, Clock clock, reactor.core.scheduler.Scheduler scheduler) {
        this.providers = providers; this.coordinator = coordinator; this.statuses = statuses; this.properties = properties; this.clock = clock;
        this.scheduler = scheduler;
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        if (!properties.enabled()) return;
        subscription = Flux.interval(java.time.Duration.ZERO, properties.scanInterval(), scheduler).onBackpressureDrop().concatMap(tick -> scan().onErrorResume(ignored -> Mono.empty()), 1).subscribe();
    }
    public Mono<Void> scan() {
        if (!properties.enabled()) return Mono.empty();
        return providers.findAll().filter(p -> p.enabled() && p.modelDiscoveryEnabled()).flatMap(provider -> statuses.read(provider.id())
                .switchIfEmpty(statuses.initialize(provider.id(), clock.millis() + Math.floorMod(provider.id().hashCode(), Math.max(1, properties.startupJitter().toMillis()))))
                .flatMap(status -> status.state().equals("WAITING") && status.lastAttemptAt() == null && status.nextRunAt() > clock.millis()
                        ? Mono.delay(java.time.Duration.ofMillis(status.nextRunAt() - clock.millis()), scheduler).thenReturn(status) : Mono.just(status))
                .filter(status -> {
                    long due = status.state().equals("SUCCESS") && status.lastSuccessAt() != null ? status.lastSuccessAt() + coordinator.interval(provider)
                            : Math.min(status.nextRunAt(), (status.lastAttemptAt() == null ? status.nextRunAt() : status.lastAttemptAt()) + coordinator.interval(provider));
                    return due <= clock.millis();
                }).flatMap(ignored -> coordinator.automatic(provider.id())).onErrorResume(ignored -> Mono.empty()), properties.concurrency()).then();
    }
    @PreDestroy public void stop() { if (subscription != null) subscription.dispose(); }
}
