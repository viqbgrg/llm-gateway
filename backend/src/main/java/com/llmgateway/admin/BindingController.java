package com.llmgateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/bindings")
public class BindingController {
    private final BindingService service;
    public BindingController(BindingService service) { this.service = service; }
    @GetMapping public Flux<BindingEntity> list(@RequestParam(required = false) String virtualModelId) { return service.list(virtualModelId); }
    @GetMapping("/{id}") public Mono<BindingEntity> get(@PathVariable String id) { return service.get(id); }
    @PostMapping public Mono<BindingEntity> create(@RequestBody AdminDtos.BindingRequest request) { return service.save(null, request); }
    @PutMapping("/{id}") public Mono<BindingEntity> update(@PathVariable String id, @RequestBody AdminDtos.BindingRequest request) { return service.save(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public Mono<Void> delete(@PathVariable String id) { return service.delete(id); }
}
