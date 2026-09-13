package com.llmgateway.admin;

import com.llmgateway.discovery.*;
import com.llmgateway.config.DiscoveryProperties;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/discovery")
public class DiscoveryController {
    private final ProviderRepository providers;
    private final DiscoveryStatusStore statuses;
    private final DiscoveryLeaseService leases;
    private final DiscoveryProperties properties;
    public DiscoveryController(ProviderRepository providers, DiscoveryStatusStore statuses, DiscoveryLeaseService leases, DiscoveryProperties properties) {
        this.providers = providers; this.statuses = statuses; this.leases = leases; this.properties = properties;
    }
    @GetMapping public Mono<DiscoveryOverview> list() { return providers.findAll().concatMap(provider -> view(provider.id())).collectList()
            .map(views -> new DiscoveryOverview(properties.enabled(), properties.defaultInterval().toMillis(), properties.missingConfirmations(), views)); }
    @GetMapping("/{id}") public Mono<DiscoveryView> get(@PathVariable String id) { return AdminValidation.required(providers.findById(id), "Provider").flatMap(provider -> view(id)); }
    private Mono<DiscoveryView> view(String id) {
        return statuses.read(id).map(status -> new DiscoveryView(id, false, false, status))
                .defaultIfEmpty(new DiscoveryView(id, false, true, null))
                .flatMap(view -> leases.busy(id).map(running -> new DiscoveryView(id, running, view.expired(), view.status())));
    }
    public record DiscoveryView(String providerId, boolean running, boolean expired, DiscoveryStatusStore.Status status) {}
    public record DiscoveryOverview(boolean schedulerEnabled, long defaultIntervalMs, int missingConfirmations, java.util.List<DiscoveryView> providers) {}
}
