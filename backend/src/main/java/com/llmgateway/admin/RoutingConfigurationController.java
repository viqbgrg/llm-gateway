package com.llmgateway.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin")
public class RoutingConfigurationController {
    private final RoutingConfigurationService service;
    public RoutingConfigurationController(RoutingConfigurationService service) { this.service = service; }
    @GetMapping("/model-rules") public Flux<ModelRuleEntity> rules() { return service.rules(); }
    @GetMapping("/model-rules/{id}") public Mono<ModelRuleEntity> rule(@PathVariable String id) { return service.rule(id); }
    @PostMapping("/model-rules") @ResponseStatus(HttpStatus.CREATED)
    public Mono<ModelRuleEntity> createRule(@Valid @RequestBody RoutingConfigurationService.RuleRequest r) { return service.saveRule(null, r); }
    @PutMapping("/model-rules/{id}") public Mono<ModelRuleEntity> updateRule(@PathVariable String id, @Valid @RequestBody RoutingConfigurationService.RuleRequest r) { return service.saveRule(id, r); }
    @DeleteMapping("/model-rules/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteRule(@PathVariable String id) { return service.deleteRule(id); }
    @GetMapping("/routing-policies") public Flux<RoutingPolicyEntity> policies() { return service.policies(); }
    @GetMapping("/routing-policies/{id}") public Mono<RoutingPolicyEntity> policy(@PathVariable String id) { return service.policy(id); }
    @PostMapping("/routing-policies") @ResponseStatus(HttpStatus.CREATED)
    public Mono<RoutingPolicyEntity> createPolicy(@Valid @RequestBody RoutingPolicyEntity.PolicyRequest r) { return service.savePolicy(null, r); }
    @PutMapping("/routing-policies/{id}") public Mono<RoutingPolicyEntity> updatePolicy(@PathVariable String id, @Valid @RequestBody RoutingPolicyEntity.PolicyRequest r) { return service.savePolicy(id, r); }
    @DeleteMapping("/routing-policies/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deletePolicy(@PathVariable String id) { return service.deletePolicy(id); }
}
