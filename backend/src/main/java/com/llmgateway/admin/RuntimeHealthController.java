package com.llmgateway.admin;

import com.llmgateway.health.RuntimeHealthService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin")
public class RuntimeHealthController {
    private final RuntimeHealthService service;
    public RuntimeHealthController(RuntimeHealthService service) { this.service = service; }
    @GetMapping("/health") public Mono<RuntimeHealthService.HealthView> all() { return service.all(); }
    @GetMapping("/health/bindings/{id}") public Mono<RuntimeHealthService.BindingView> binding(@PathVariable String id) { return service.binding(id); }
    @GetMapping("/dashboard") public Mono<RuntimeHealthService.DashboardView> dashboard() { return service.dashboard(); }
}
