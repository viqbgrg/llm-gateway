package com.llmgateway.discovery;

import com.llmgateway.admin.*;
import com.llmgateway.config.DiscoveryProperties;
import com.llmgateway.infrastructure.GatewayMetrics;
import com.llmgateway.infrastructure.credentials.CredentialService;
import com.llmgateway.inference.*;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class DiscoveryCoordinator {
    private final ProviderRepository providers;
    private final ProviderModelDiscovery discovery;
    private final CredentialService credentials;
    private final DiscoveryLeaseService leases;
    private final DiscoveryReconciler reconciler;
    private final DiscoveryStatusStore statuses;
    private final DiscoveryProperties properties;
    private final Clock clock;
    private final GatewayMetrics metrics;
    private final com.llmgateway.infrastructure.RuntimeStateCleanup runtime;
    public DiscoveryCoordinator(ProviderRepository providers, ProviderModelDiscovery discovery, CredentialService credentials,
                                 DiscoveryLeaseService leases, DiscoveryReconciler reconciler, DiscoveryStatusStore statuses,
                                 DiscoveryProperties properties, Clock clock, GatewayMetrics metrics, com.llmgateway.infrastructure.RuntimeStateCleanup runtime) {
        this.providers = providers; this.discovery = discovery; this.credentials = credentials; this.leases = leases;
        this.reconciler = reconciler; this.statuses = statuses; this.properties = properties; this.clock = clock; this.metrics = metrics;
        this.runtime = runtime;
    }
    public Mono<DiscoveryReconciler.Result> manual(String providerId) { return run(providerId, false); }
    public Mono<DiscoveryReconciler.Result> automatic(String providerId) { return run(providerId, true); }
    private Mono<DiscoveryReconciler.Result> run(String providerId, boolean automatic) {
        Mono<DiscoveryLeaseService.Lease> lease = leases.acquire(providerId, properties.leaseTtl());
        if (!automatic) lease = lease.repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(50)))
                .timeout(properties.manualWait(), Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Provider discovery is busy; retry later")));
        return Mono.usingWhen(lease, owned -> providers.findById(providerId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found")))
                .filter(provider -> !automatic || provider.enabled() && provider.modelDiscoveryEnabled())
                .flatMap(provider -> reconciler.allocate(providerId).flatMap(generation -> perform(provider, owned, generation, automatic))),
                leases::release, (owned, error) -> leases.release(owned), leases::release);
    }
    private Mono<DiscoveryReconciler.Result> perform(ProviderEntity provider, DiscoveryLeaseService.Lease lease, long generation, boolean reconcile) {
        long began = clock.millis(); long started = System.nanoTime(); long interval = interval(provider);
        return statuses.read(provider.id()).defaultIfEmpty(new DiscoveryStatusStore.Status(provider.id(), "WAITING", null, null, began, 0, 0, 0, 0, 0, 0, 0, null, 0, 0))
                .flatMap(previous -> {
                    var running = new DiscoveryStatusStore.Status(provider.id(), "RUNNING", began, previous.lastSuccessAt(), began + interval, 0,
                            0, 0, 0, 0, 0, 0, null, generation, previous.consecutiveFailures());
                    Mono<DiscoveryReconciler.Result> operation = Mono.defer(() -> discovery.discover(credentials.access(provider)))
                            .flatMap(catalog -> reconciler.apply(provider, lease, generation, catalog, reconcile))
                            .flatMap(result -> result.created() + result.removed() + result.reappeared() > 0
                                    ? runtime.providerChanged(provider.id()).thenReturn(result) : Mono.just(result));
                    return statuses.write(running, interval)
                            .then(Mono.firstWithSignal(operation, leases.heartbeat(lease).then(Mono.error(new GatewayException(GatewayError.CONFIGURATION_CHANGED)))))
                            .flatMap(result -> {
                                metrics.discovery("SUCCESS", System.nanoTime() - started);
                                return statuses.write(new DiscoveryStatusStore.Status(provider.id(), "SUCCESS", began, clock.millis(), clock.millis() + interval,
                                        clock.millis() - began, result.created(), result.updated(), result.total(), result.missing(), result.removed(), result.reappeared(),
                                        null, generation, 0), interval).onErrorResume(ignored -> Mono.empty()).thenReturn(result);
                            }).onErrorResume(error -> {
                                metrics.discovery("FAILED", System.nanoTime() - started);
                                String code = error instanceof ProviderAccessException access ? access.code() : error instanceof GatewayException safe ? safe.error().name() : "DISCOVERY_FAILED";
                                int failures = Math.min(20, previous.consecutiveFailures() + 1);
                                long backoff = Math.min(interval, 1000L * (1L << failures));
                                return statuses.write(new DiscoveryStatusStore.Status(provider.id(), "FAILED", began, previous.lastSuccessAt(), clock.millis() + backoff,
                                        clock.millis() - began, 0, 0, 0, 0, 0, 0, code, generation, failures), interval)
                                        .onErrorResume(ignored -> Mono.empty()).then(Mono.error(error));
                            });
                });
    }
    public long interval(ProviderEntity provider) { return provider.modelDiscoveryIntervalMs() > 0 ? provider.modelDiscoveryIntervalMs() : properties.defaultInterval().toMillis(); }
}
