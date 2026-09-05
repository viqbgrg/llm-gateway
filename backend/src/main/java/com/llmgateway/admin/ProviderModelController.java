package com.llmgateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/provider-models")
public class ProviderModelController {
    private final ProviderModelService service;
    public ProviderModelController(ProviderModelService service) { this.service = service; }
    @GetMapping public Flux<ProviderModelEntity> list(@RequestParam(required = false) String providerId) { return service.list(providerId); }
    @GetMapping("/{id}") public Mono<ProviderModelEntity> get(@PathVariable String id) { return service.get(id); }
    @PostMapping public Mono<ProviderModelEntity> create(@RequestBody AdminDtos.ProviderModelRequest request) { return service.save(null, request); }
    @PutMapping("/{id}") public Mono<ProviderModelEntity> update(@PathVariable String id, @RequestBody AdminDtos.ProviderModelRequest request) { return service.save(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public Mono<Void> delete(@PathVariable String id) { return service.delete(id); }
}
