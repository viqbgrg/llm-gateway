package com.llmgateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/virtual-models")
public class VirtualModelController {
    private final VirtualModelService service;
    public VirtualModelController(VirtualModelService service) { this.service = service; }
    @GetMapping public Flux<VirtualModelEntity> list() { return service.list(); }
    @GetMapping("/{id}") public Mono<VirtualModelEntity> get(@PathVariable String id) { return service.get(id); }
    @PostMapping public Mono<VirtualModelEntity> create(@RequestBody AdminDtos.VirtualModelRequest request) { return service.save(null, request); }
    @PutMapping("/{id}") public Mono<VirtualModelEntity> update(@PathVariable String id, @RequestBody AdminDtos.VirtualModelRequest request) { return service.save(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public Mono<Void> delete(@PathVariable String id) { return service.delete(id); }
}
