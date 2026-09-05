package com.llmgateway.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/providers")
public class ProviderController {
    private final ProviderService service;
    public ProviderController(ProviderService service) { this.service = service; }
    @GetMapping public Flux<AdminDtos.ProviderResponse> list() { return service.list(); }
    @GetMapping("/{id}") public Mono<AdminDtos.ProviderResponse> get(@PathVariable String id) { return service.get(id); }
    @PostMapping public Mono<AdminDtos.ProviderResponse> create(@Valid @RequestBody AdminDtos.ProviderRequest request) { return service.save(null, request); }
    @PutMapping("/{id}") public Mono<AdminDtos.ProviderResponse> update(@PathVariable String id, @Valid @RequestBody AdminDtos.ProviderRequest request) { return service.save(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public Mono<Void> delete(@PathVariable String id) { return service.delete(id); }
}
